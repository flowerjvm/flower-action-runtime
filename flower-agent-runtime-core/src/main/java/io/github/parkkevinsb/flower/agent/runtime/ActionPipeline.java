package io.github.parkkevinsb.flower.agent.runtime;

import java.util.List;
import java.util.Map;

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
        bestEffortRuntimeFailureAudit(session, message, cause);
        return session.result();
    }

    static StageOutcome recordProposal(ActionExecutionSession session) {
        session.record(AuditEventType.ACTION_PROPOSED, Map.of("origin", session.proposal().origin().name()));
        return StageOutcome.CONTINUE;
    }

    static StageOutcome reserveDuplicate(ActionExecutionSession session) {
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
        return StageOutcome.SHORT_CIRCUIT;
    }

    static StageOutcome resolveAction(ActionExecutionSession session) {
        ActionExecutor executor = session.registry().findExecutor(session.proposal().actionId()).orElse(null);
        if (executor == null) {
            ActionExecutionResult result =
                    ActionExecutionResult.denied("Action is not registered: " + session.proposal().actionId());
            session.result(result);
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
        ValidationResult validation =
                session.inputValidator().validate(session.proposal(), session.definition(), session.context());
        session.validationResult(validation);
        session.record(AuditEventType.VALIDATION_COMPLETED, Map.of("valid", validation.valid()));
        if (!validation.valid()) {
            ActionExecutionResult result =
                    ActionExecutionResult.validationFailed(String.join("; ", validation.violations()));
            session.result(result);
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        return StageOutcome.CONTINUE;
    }

    static StageOutcome evaluatePolicy(ActionExecutionSession session) {
        PolicyDecision decision =
                session.policyGate().evaluate(session.proposal(), session.definition(), session.context());
        session.policyDecision(decision);
        session.record(AuditEventType.POLICY_EVALUATED, Map.of("type", decision.type().name()));
        if (decision.requiresApproval()) {
            ApprovalRequest approval = session.approvalGate()
                    .requestApproval(session.proposal(), session.definition(), session.context(), decision);
            session.result(ActionExecutionResult.pendingApproval(
                    decision.reason(),
                    Map.of("approvalId", approval.approvalId())));
            session.record(AuditEventType.APPROVAL_REQUESTED, Map.of("approvalId", approval.approvalId()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        if (!decision.allowedToExecuteNow()) {
            ActionExecutionResult result = ActionExecutionResult.denied(decision.reason());
            session.result(result);
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        return StageOutcome.CONTINUE;
    }

    static StageOutcome executeAction(ActionExecutionSession session) {
        ActionExecutionContext actionContext = new ActionExecutionContext(
                session.context(),
                session.proposal(),
                session.definition(),
                session.proposal().input());
        if (session.policyDecision().type() == PolicyDecisionType.REQUIRE_DRY_RUN) {
            ActionExecutionResult dryRun = session.executor().dryRun(actionContext);
            session.record(AuditEventType.DRY_RUN_COMPLETED, dryRun.output());
        }
        session.record(AuditEventType.ACTION_EXECUTION_STARTED, Map.of());
        session.markActionExecutionStarted();
        ActionExecutionResult result;
        try {
            result = session.executor().execute(actionContext);
        } catch (RuntimeException exception) {
            result = ActionExecutionResult.failed(failureMessage(exception));
            session.result(result);
            session.record(AuditEventType.ACTION_EXECUTION_FAILED, Map.of("message", result.message()));
            return StageOutcome.CONTINUE;
        }
        session.result(result);
        session.record(result.terminalSuccess()
                ? AuditEventType.ACTION_EXECUTION_COMPLETED
                : AuditEventType.ACTION_EXECUTION_FAILED, result.output());
        return StageOutcome.CONTINUE;
    }

    static StageOutcome finalizeExecution(ActionExecutionSession session) {
        if (session.duplicateDecision() != null
                && session.duplicateDecision().type() == DuplicateActionDecisionType.ACCEPT) {
            if (isDuplicateCacheable(session.result())) {
                session.duplicateActionPolicy().complete(session.proposal(), session.result());
            } else {
                session.duplicateActionPolicy().release(session.proposal(), null);
            }
        }
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

    private static boolean isDuplicateCacheable(ActionExecutionResult result) {
        return result.status() != ActionExecutionStatus.PENDING_APPROVAL;
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
