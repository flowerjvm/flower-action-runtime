package io.github.flowerjvm.flower.action.runtime.pipeline;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecisionType;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalRequest;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEventType;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecision;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecisionType;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecisionType;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The controlled action pipeline: the single source of truth for stage order, branching, and audit payloads.
 *
 * <pre>
 * record-proposal
 * -&gt; reserve-duplicate
 * -&gt; resolve-action
 * -&gt; validate-input
 * -&gt; evaluate-policy
 * -&gt; execute-action
 * -&gt; record-result  (finalize, always runs)
 * </pre>
 *
 * <p>Gates run in order until one short-circuits; the finalize stage always runs. Every execution backend
 * (the direct {@link DefaultActionRuntime} and the Flower Flow backend) executes these same stages so their
 * governance behavior and audit trail cannot diverge.</p>
 */
public final class ActionPipeline {

    /** A pipeline stage paired with the stable name a Flow backend uses for its Step. */
    public record NamedStage(String name, ActionStage stage) {
    }

    private ActionPipeline() {
    }

    /** Ordered gate stages. Any of these may short-circuit; the finalize stage is applied separately. */
    public static List<NamedStage> gates() {
        return List.of(
                new NamedStage("record-proposal", ActionPipeline::recordProposal),
                new NamedStage("reserve-duplicate", ActionPipeline::reserveDuplicate),
                new NamedStage("resolve-action", ActionPipeline::resolveAction),
                new NamedStage("validate-input", ActionPipeline::validateInput),
                new NamedStage("evaluate-policy", ActionPipeline::evaluatePolicy),
                new NamedStage("execute-action", ActionPipeline::executeAction));
    }

    /** Terminal stage that always runs, even after a short-circuit, to finalize idempotency bookkeeping. */
    public static NamedStage finalizeStage() {
        return new NamedStage("record-result", ActionPipeline::finalizeExecution);
    }

    /** Runs the whole pipeline synchronously in the current thread and returns the resulting outcome. */
    public static ActionExecutionResult run(ActionExecutionSession session) {
        try {
            for (NamedStage gate : gates()) {
                if (gate.stage().execute(session) == StageOutcome.SHORT_CIRCUIT) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            return failRuntime(session, exception);
        }

        try {
            finalizeStage().stage().execute(session);
        } catch (RuntimeException exception) {
            return failFinalize(session, exception);
        }
        return session.result();
    }

    public static ActionExecutionResult resumeApproved(ActionExecutionSession session) {
        try {
            session.record(AuditEventType.APPROVAL_APPROVED, Map.of("approvalId", session.run().approvalId()));
            for (ActionStage stage : List.<ActionStage>of(
                    ActionPipeline::resolveAction,
                    ActionPipeline::validateInput,
                    ActionPipeline::executeAction)) {
                if (stage.execute(session) == StageOutcome.SHORT_CIRCUIT) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            return failRuntime(session, exception);
        }

        try {
            finalizeStage().stage().execute(session);
        } catch (RuntimeException exception) {
            return failFinalize(session, exception);
        }
        return session.result();
    }

    public static ActionExecutionResult resume(ActionExecutionSession session, ApprovalDecision decision) {
        ActionRun run = session.run();
        session.policyDecision(new PolicyDecision(
                run.policyDecisionType() == null ? PolicyDecisionType.ALLOW : run.policyDecisionType(),
                run.policyReason(),
                Map.of()));
        if (decision.type() == ApprovalDecisionType.APPROVED) {
            return resumeApproved(session);
        }
        if (decision.type() == ApprovalDecisionType.EXPIRED) {
            return expire(session);
        }
        return reject(session, decision.reason());
    }

    public static ActionExecutionResult reject(ActionExecutionSession session, String reason) {
        String rejectionReason = reason == null ? "" : reason.trim();
        try {
            ActionExecutionResult result = ActionExecutionResult.denied(
                    "Approval rejected" + (rejectionReason.isBlank() ? "" : ": " + rejectionReason));
            session.result(result);
            session.updateRun(run -> run.toBuilder()
                    .status(ActionRunStatus.DENIED)
                    .result(result)
                    .failureReason(result.message())
                    .build());
            session.record(AuditEventType.APPROVAL_REJECTED, Map.of(
                    "approvalId", session.run().approvalId(),
                    "reason", rejectionReason));
        } catch (RuntimeException exception) {
            return failRuntime(session, exception);
        }

        try {
            finalizeStage().stage().execute(session);
        } catch (RuntimeException exception) {
            return failFinalize(session, exception);
        }
        return session.result();
    }

    public static ActionExecutionResult expire(ActionExecutionSession session) {
        try {
            ActionExecutionResult result = ActionExecutionResult.denied("Approval expired");
            session.result(result);
            session.updateRun(run -> run.toBuilder()
                    .status(ActionRunStatus.EXPIRED)
                    .result(result)
                    .failureReason(result.message())
                    .build());
            session.record(AuditEventType.APPROVAL_EXPIRED, Map.of("approvalId", session.run().approvalId()));
        } catch (RuntimeException exception) {
            return failRuntime(session, exception);
        }

        try {
            finalizeStage().stage().execute(session);
        } catch (RuntimeException exception) {
            return failFinalize(session, exception);
        }
        return session.result();
    }

    /**
     * Converts a runtime-envelope failure into the same failed result and best-effort audit/duplicate cleanup
     * for every backend.
     *
     * <p>This is intentionally not used for normal executor failures. Those are action execution failures and are
     * handled in {@link #executeAction(ActionExecutionSession)}. This method is for gate/runtime failures such as
     * validator, policy, duplicate, audit, or Flow step failures.</p>
     */
    public static ActionExecutionResult failRuntime(ActionExecutionSession session, Throwable cause) {
        String message = failureMessage(cause);
        if (!session.hasResult()) {
            session.result(ActionExecutionResult.failed(message));
        }
        bestEffortRuntimeFailureRunUpdate(session, message);
        bestEffortRuntimeFailureAudit(session, message, cause);
        bestEffortReleaseReservedDuplicate(session, cause);
        return session.result();
    }

    /**
     * Handles a failure in the final duplicate bookkeeping stage without changing the already-produced action result.
     */
    public static ActionExecutionResult failFinalize(ActionExecutionSession session, Throwable cause) {
        String message = failureMessage(cause);
        if (!session.hasResult()) {
            session.result(ActionExecutionResult.failed(message));
        }
        bestEffortFinalizeFailureRunUpdate(session, message);
        bestEffortRuntimeFailureAudit(session, message, cause);
        return session.result();
    }

    static StageOutcome recordProposal(ActionExecutionSession session) {
        session.beginRun("record-proposal");
        session.record(AuditEventType.ACTION_PROPOSED, Map.of("origin", session.proposal().origin().name()));
        return StageOutcome.CONTINUE;
    }

    static StageOutcome reserveDuplicate(ActionExecutionSession session) {
        session.enterStage("reserve-duplicate");
        DuplicateActionDecision decision =
                session.duplicateActionPolicy().reserve(session.proposal(), session.context());
        session.duplicateDecision(decision);
        if (decision.type() == DuplicateActionDecisionType.ACCEPT) {
            return StageOutcome.CONTINUE;
        }
        session.record(AuditEventType.ACTION_DUPLICATE, Map.of("decision", decision.type().name()));
        session.result(decision.type() == DuplicateActionDecisionType.RETURN_EXISTING
                ? decision.existingResult()
                : ActionExecutionResult.denied(decision.reason()));
        session.updateRun(run -> run.toBuilder()
                .status(actionRunStatus(session.result()))
                .result(session.result())
                .failureReason(session.result().message())
                .build());
        return StageOutcome.SHORT_CIRCUIT;
    }

    static StageOutcome resolveAction(ActionExecutionSession session) {
        session.enterStage("resolve-action");
        session.updateRun(run -> run.toBuilder().status(ActionRunStatus.VALIDATING).build());
        ActionExecutor executor = session.registry().findExecutor(session.proposal().actionId()).orElse(null);
        if (executor == null) {
            ActionExecutionResult result =
                    ActionExecutionResult.denied("Action is not registered: " + session.proposal().actionId());
            session.result(result);
            session.updateRun(run -> run.toBuilder()
                    .status(ActionRunStatus.DENIED)
                    .result(result)
                    .failureReason(result.message())
                    .build());
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        session.executor(executor);
        session.definition(executor.definition());
        session.record(AuditEventType.ACTION_RESOLVED, Map.of(
                "riskLevel", executor.definition().riskLevel().name(),
                "effect", executor.definition().effect().name()));
        return StageOutcome.CONTINUE;
    }

    static StageOutcome validateInput(ActionExecutionSession session) {
        session.enterStage("validate-input");
        ValidationResult validation =
                session.inputValidator().validate(session.proposal(), session.definition(), session.context());
        session.validationResult(validation);
        session.record(AuditEventType.VALIDATION_COMPLETED, Map.of("valid", validation.valid()));
        if (!validation.valid()) {
            ActionExecutionResult result =
                    ActionExecutionResult.validationFailed(String.join("; ", validation.violations()));
            session.result(result);
            session.updateRun(run -> run.toBuilder()
                    .status(ActionRunStatus.DENIED)
                    .result(result)
                    .failureReason(result.message())
                    .build());
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        return StageOutcome.CONTINUE;
    }

    static StageOutcome evaluatePolicy(ActionExecutionSession session) {
        session.enterStage("evaluate-policy");
        PolicyDecision decision =
                session.policyGate().evaluate(session.proposal(), session.definition(), session.context());
        session.policyDecision(decision);
        session.updateRun(run -> run.toBuilder()
                .status(ActionRunStatus.POLICY_EVALUATED)
                .policyDecisionType(decision.type())
                .policyReason(decision.reason())
                .build());
        session.record(AuditEventType.POLICY_EVALUATED, Map.of("type", decision.type().name()));
        if (decision.requiresApproval()) {
            ApprovalRequest approval = session.approvalGate()
                    .requestApproval(session.proposal(), session.definition(), session.context(), decision);
            session.result(ActionExecutionResult.pendingApproval(
                    decision.reason(),
                    Map.of(
                            "approvalId", approval.approvalId(),
                            "runId", session.run().runId())));
            session.updateRun(run -> run.toBuilder()
                    .status(ActionRunStatus.WAITING_APPROVAL)
                    .approvalId(approval.approvalId())
                    .result(session.result())
                    .build());
            session.record(AuditEventType.APPROVAL_REQUESTED, Map.of("approvalId", approval.approvalId()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        if (!decision.allowedToExecuteNow()) {
            ActionExecutionResult result = ActionExecutionResult.denied(decision.reason());
            session.result(result);
            session.updateRun(run -> run.toBuilder()
                    .status(ActionRunStatus.DENIED)
                    .result(result)
                    .failureReason(result.message())
                    .build());
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        return StageOutcome.CONTINUE;
    }

    static StageOutcome executeAction(ActionExecutionSession session) {
        session.enterStage("execute-action");
        ActionExecutionContext actionContext = new ActionExecutionContext(
                session.context(),
                session.proposal(),
                session.definition(),
                session.proposal().input());
        if (session.policyDecision().type() == PolicyDecisionType.REQUIRE_DRY_RUN) {
            if (!session.definition().dryRunSupported()) {
                ActionExecutionResult result = ActionExecutionResult.denied(
                        "Dry-run is required but not supported: " + session.definition().actionId());
                session.result(result);
                session.updateRun(run -> run.toBuilder()
                        .status(ActionRunStatus.DENIED)
                        .result(result)
                        .failureReason(result.message())
                        .build());
                session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
                return StageOutcome.SHORT_CIRCUIT;
            }
            ActionExecutionResult dryRun = session.executor().dryRun(actionContext);
            session.record(AuditEventType.DRY_RUN_COMPLETED, Map.of(
                    "status", dryRun.status().name(),
                    "message", dryRun.message(),
                    "output", dryRun.output()));
            if (!dryRun.terminalSuccess()) {
                session.result(dryRun);
                session.updateRun(run -> run.toBuilder()
                        .status(actionRunStatus(dryRun))
                        .result(dryRun)
                        .failureReason(dryRun.message())
                        .build());
                return StageOutcome.SHORT_CIRCUIT;
            }
        }
        session.updateRun(run -> run.toBuilder()
                .status(ActionRunStatus.RUNNING)
                .attemptToken(UUID.randomUUID().toString())
                .build());
        session.record(AuditEventType.ACTION_EXECUTION_STARTED, Map.of());
        session.markActionExecutionStarted();
        ActionExecutionResult result;
        try {
            result = session.executor().execute(actionContext);
        } catch (RuntimeException exception) {
            result = ActionExecutionResult.failed(failureMessage(exception));
            session.result(result);
            ActionExecutionResult failedResult = result;
            session.updateRun(run -> run.toBuilder()
                    .status(ActionRunStatus.FAILED)
                    .result(failedResult)
                    .failureReason(failedResult.message())
                    .build());
            session.record(AuditEventType.ACTION_EXECUTION_FAILED, Map.of("message", result.message()));
            return StageOutcome.CONTINUE;
        }
        session.result(result);
        ActionExecutionResult executionResult = result;
        session.updateRun(run -> run.toBuilder()
                .status(actionRunStatus(executionResult))
                .result(executionResult)
                .failureReason(executionResult.terminalSuccess() ? "" : executionResult.message())
                .build());
        session.record(result.terminalSuccess()
                ? AuditEventType.ACTION_EXECUTION_COMPLETED
                : AuditEventType.ACTION_EXECUTION_FAILED, result.output());
        return StageOutcome.CONTINUE;
    }

    static StageOutcome finalizeExecution(ActionExecutionSession session) {
        if (session.duplicateDecision() != null
                && session.duplicateDecision().type() == DuplicateActionDecisionType.ACCEPT) {
            if (session.result().status() != ActionExecutionStatus.PENDING_APPROVAL) {
                session.duplicateActionPolicy().complete(session.proposal(), session.result());
            }
        }
        session.updateRun(run -> {
            ActionRun.Builder builder = run.toBuilder().result(session.result());
            if (!run.status().isTerminal()) {
                builder.status(actionRunStatus(session.result()));
            }
            return builder.build();
        });
        return StageOutcome.CONTINUE;
    }

    private static String failureMessage(Throwable cause) {
        if (cause == null) {
            return "Action runtime failed.";
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message;
    }

    private static ActionRunStatus actionRunStatus(ActionExecutionResult result) {
        return switch (result.status()) {
            case SUCCEEDED -> ActionRunStatus.SUCCEEDED;
            case FAILED -> ActionRunStatus.FAILED;
            case DENIED, VALIDATION_FAILED -> ActionRunStatus.DENIED;
            case PENDING_APPROVAL -> ActionRunStatus.WAITING_APPROVAL;
        };
    }

    private static void bestEffortRuntimeFailureRunUpdate(ActionExecutionSession session, String message) {
        try {
            if (!session.run().status().isTerminal()) {
                session.updateRun(run -> run.toBuilder()
                        .status(ActionRunStatus.RUNTIME_FAILED)
                        .failureReason(message)
                        .result(session.result())
                        .build());
            }
        } catch (Throwable ignored) {
            // Failure handling must not fail again while updating run state.
        }
    }

    private static void bestEffortFinalizeFailureRunUpdate(ActionExecutionSession session, String message) {
        try {
            session.updateRun(run -> run.toBuilder()
                    .result(session.result())
                    .failureReason(message)
                    .build());
        } catch (Throwable ignored) {
            // Failure handling must not fail again while updating run state.
        }
    }

    private static void bestEffortRuntimeFailureAudit(ActionExecutionSession session, String message, Throwable cause) {
        try {
            session.record(AuditEventType.ACTION_RUNTIME_FAILED, Map.of("message", message));
        } catch (Throwable auditFailure) {
            suppress(cause, auditFailure);
        }
    }

    private static void bestEffortReleaseReservedDuplicate(ActionExecutionSession session, Throwable cause) {
        if (session.duplicateDecision() == null
                || session.duplicateDecision().type() != DuplicateActionDecisionType.ACCEPT) {
            return;
        }
        if (session.actionExecutionStarted()) {
            return;
        }
        try {
            session.duplicateActionPolicy().release(session.proposal(), cause);
        } catch (Throwable releaseFailure) {
            suppress(cause, releaseFailure);
        }
    }

    private static void suppress(Throwable cause, Throwable secondary) {
        if (cause == null || secondary == null || cause == secondary) {
            return;
        }
        try {
            cause.addSuppressed(secondary);
        } catch (Throwable ignored) {
            // Best-effort failure handling must not fail while recording suppressed failures.
        }
    }
}
