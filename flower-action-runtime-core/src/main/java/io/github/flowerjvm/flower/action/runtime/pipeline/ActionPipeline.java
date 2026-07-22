package io.github.flowerjvm.flower.action.runtime.pipeline;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.AsyncActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecisionType;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalRequest;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEventType;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecision;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecisionType;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecisionType;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.ConcurrentActionRunUpdateException;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * The controlled action pipeline: the single source of truth for stage order, branching, and audit payloads.
 *
 * <pre>
 * record-proposal
 * -&gt; resolve-action
 * -&gt; validate-input
 * -&gt; evaluate-policy
 * -&gt; reserve-duplicate
 * -&gt; request-approval
 * -&gt; pre-execution-check
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
                new NamedStage("resolve-action", ActionPipeline::resolveAction),
                new NamedStage("validate-input", ActionPipeline::validateInput),
                new NamedStage("evaluate-policy", ActionPipeline::evaluatePolicy),
                new NamedStage("reserve-duplicate", ActionPipeline::reserveDuplicate),
                new NamedStage("request-approval", ActionPipeline::requestApproval),
                new NamedStage("pre-execution-check", ActionPipeline::preExecutionCheck),
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
                    ActionPipeline::reevaluatePolicyAfterApproval,
                    ActionPipeline::preExecutionCheck,
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
                    "APPROVAL_REJECTED",
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
            ActionExecutionResult result = ActionExecutionResult.denied("APPROVAL_EXPIRED", "Approval expired");
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
        if (cause instanceof ConcurrentActionRunUpdateException) {
            return concurrentUpdateResult(session);
        }
        String message = failureMessage(cause);
        if (!session.hasResult()) {
            session.result(ActionExecutionResult.failed("ACTION_RUNTIME_FAILED", message));
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
        if (cause instanceof ConcurrentActionRunUpdateException) {
            return concurrentUpdateResult(session);
        }
        String message = failureMessage(cause);
        if (!session.hasResult()) {
            session.result(ActionExecutionResult.failed("ACTION_FINALIZATION_FAILED", message));
        }
        bestEffortFinalizeFailureRunUpdate(session, message);
        bestEffortRuntimeFailureAudit(session, message, cause);
        return session.result();
    }

    static StageOutcome recordProposal(ActionExecutionSession session) {
        session.beginRun("record-proposal");
        session.record(AuditEventType.ACTION_PROPOSED, Map.of(
                "requestChannel", session.proposal().requestChannel().name(),
                "proposerType", session.proposal().proposerType().name(),
                "proposerId", session.proposal().requesterId(),
                "executionPrincipalId", session.context().userId()));
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
                : ActionExecutionResult.denied("DUPLICATE_ACTION", decision.reason()));
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
                    ActionExecutionResult.denied(
                            "ACTION_NOT_REGISTERED",
                            "Action is not registered: " + session.proposal().actionId());
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
        if (!decision.allowedToExecuteNow() && !decision.requiresApproval()) {
            ActionExecutionResult result = ActionExecutionResult.denied("POLICY_DENIED", decision.reason());
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

    static StageOutcome requestApproval(ActionExecutionSession session) {
        session.enterStage("request-approval");
        PolicyDecision decision = session.policyDecision();
        if (!decision.requiresApproval()) {
            return StageOutcome.CONTINUE;
        }
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

    static StageOutcome reevaluatePolicyAfterApproval(ActionExecutionSession session) {
        session.enterStage("reevaluate-policy");
        PolicyDecision approvedDecision = session.policyDecision();
        PolicyDecision currentDecision = session.policyGate()
                .evaluate(session.proposal(), session.definition(), session.context());
        session.policyDecision(currentDecision);
        session.updateRun(run -> run.toBuilder()
                .status(ActionRunStatus.POLICY_EVALUATED)
                .policyDecisionType(currentDecision.type())
                .policyReason(currentDecision.reason())
                .build());
        session.record(AuditEventType.POLICY_REEVALUATED, Map.of(
                "previousType", approvedDecision.type().name(),
                "currentType", currentDecision.type().name(),
                "reason", currentDecision.reason()));

        if (currentDecision.type() == PolicyDecisionType.ALLOW
                || currentDecision.type() == PolicyDecisionType.REQUIRE_DRY_RUN) {
            return StageOutcome.CONTINUE;
        }
        if (currentDecision.type() == PolicyDecisionType.REQUIRE_APPROVAL
                && approvedDecision.type() == PolicyDecisionType.REQUIRE_APPROVAL) {
            return StageOutcome.CONTINUE;
        }

        String reason = currentDecision.reason().isBlank()
                ? "Policy no longer permits execution after approval."
                : currentDecision.reason();
        ActionExecutionResult result = ActionExecutionResult.denied("POLICY_REVALIDATION_DENIED", reason);
        session.result(result);
        session.updateRun(run -> run.toBuilder()
                .status(ActionRunStatus.DENIED)
                .result(result)
                .failureReason(result.message())
                .build());
        session.record(AuditEventType.ACTION_DENIED, Map.of(
                "code", result.code(),
                "reason", result.message()));
        return StageOutcome.SHORT_CIRCUIT;
    }

    static StageOutcome preExecutionCheck(ActionExecutionSession session) {
        session.enterStage("pre-execution-check");
        PreExecutionDecision decision = session.preExecutionGuard().check(
                session.proposal(),
                session.definition(),
                session.context(),
                session.policyDecision());
        session.record(AuditEventType.PRE_EXECUTION_CHECKED, Map.of(
                "allowed", decision.allowed(),
                "code", decision.code(),
                "reason", decision.reason(),
                "metadata", decision.metadata()));
        if (decision.allowed()) {
            return StageOutcome.CONTINUE;
        }

        ActionExecutionResult result = ActionExecutionResult.denied(decision.code(), decision.reason());
        session.result(result);
        session.updateRun(run -> run.toBuilder()
                .status(ActionRunStatus.DENIED)
                .result(result)
                .failureReason(result.message())
                .build());
        session.record(AuditEventType.ACTION_DENIED, Map.of(
                "code", result.code(),
                "reason", result.message()));
        return StageOutcome.SHORT_CIRCUIT;
    }

    static StageOutcome executeAction(ActionExecutionSession session) {
        session.enterStage("execute-action");
        ActionExecutionContext dryRunContext = new ActionExecutionContext(
                session.context(),
                session.proposal(),
                session.definition(),
                session.proposal().input());
        if (session.policyDecision().type() == PolicyDecisionType.REQUIRE_DRY_RUN) {
            if (!session.definition().dryRunSupported()) {
                ActionExecutionResult result = ActionExecutionResult.denied(
                        "DRY_RUN_REQUIRED_BUT_UNSUPPORTED",
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
            ActionExecutionResult dryRun = session.executor().dryRun(dryRunContext);
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

        if ((session.executor() instanceof AsyncActionExecutor
                || session.executor() instanceof DeferredActionExecutor)
                && !session.runStore().supportsResumableRuns()) {
            ActionExecutionResult result = ActionExecutionResult.correctableFailure(
                    "RUN_STORE_REQUIRED_FOR_DEFERRED_EXECUTION",
                    "Deferred and asynchronous actions require a queryable RunStore.");
            return completeExecution(session, result);
        }

        String attemptToken = UUID.randomUUID().toString();
        session.updateRun(run -> run.toBuilder()
                .status(ActionRunStatus.RUNNING)
                .attemptToken(attemptToken)
                .externalOperationId("")
                .externalOperationMetadata(Map.of())
                .build());
        session.record(AuditEventType.ACTION_EXECUTION_STARTED, Map.of());
        session.markActionExecutionStarted();
        ActionExecutionContext actionContext = new ActionExecutionContext(
                session.context(),
                session.proposal(),
                session.definition(),
                session.proposal().input(),
                attemptToken);
        ActionDispatch dispatch;
        try {
            dispatch = session.executor().dispatch(actionContext);
        } catch (RuntimeException exception) {
            return completeExecution(session, ActionExecutionResult.failed(
                    "ACTION_EXECUTION_EXCEPTION",
                    failureMessage(exception)));
        }

        if (dispatch == null) {
            return completeExecution(session, ActionExecutionResult.failed(
                    "ACTION_DISPATCH_MISSING",
                    "Action executor returned no dispatch result."));
        }
        if (dispatch instanceof ActionDispatch.Completed completed) {
            return completeExecution(session, completed.result());
        }
        if (dispatch instanceof ActionDispatch.Awaiting awaiting) {
            return parkDeferred(session, awaiting.operationId(), awaiting.dueAt(), awaiting.metadata());
        }

        ActionDispatch.Async async = (ActionDispatch.Async) dispatch;
        try {
            var future = async.completion().toCompletableFuture();
            if (future.isDone()) {
                return completeExecution(session, completedAsyncResult(future.join()));
            }
        } catch (CompletionException exception) {
            return completeExecution(session, asyncFailure(exception));
        } catch (UnsupportedOperationException ignored) {
            // Some CompletionStage implementations cannot expose a CompletableFuture. Register completion below.
        } catch (RuntimeException exception) {
            return completeExecution(session, asyncFailure(exception));
        }

        StageOutcome outcome = parkDeferred(session, async.operationId(), async.dueAt(), async.metadata());
        session.afterFinalize(() -> async.completion().whenComplete((result, failure) ->
                session.deferredCompletionSink().complete(
                        session.run().runId(),
                        attemptToken,
                        failure == null ? completedAsyncResult(result) : asyncFailure(failure))));
        return outcome;
    }

    public static ActionExecutionResult completeDeferred(
            ActionExecutionSession session,
            ActionExecutionResult result) {
        try {
            ActionExecutionResult terminalResult = result == null
                    ? ActionExecutionResult.failed(
                            "ACTION_RESULT_MISSING",
                            "Deferred action completed without a result.")
                    : result;
            if (!terminalResult.terminal()) {
                throw new IllegalArgumentException("Deferred completion result must be terminal");
            }
            completeExecution(session, terminalResult);
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

    private static StageOutcome parkDeferred(
            ActionExecutionSession session,
            String operationId,
            java.time.Instant dueAt,
            Map<String, Object> metadata) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("runId", session.run().runId());
        output.put("operationId", operationId);
        if (dueAt != null) {
            output.put("dueAt", dueAt.toString());
        }
        ActionExecutionResult accepted = ActionExecutionResult.accepted(
                "ACTION_DEFERRED",
                "Action was dispatched and is awaiting completion.",
                output);
        session.result(accepted);
        session.updateRun(run -> run.toBuilder()
                .status(ActionRunStatus.WAITING_EXTERNAL)
                .externalOperationId(operationId)
                .externalOperationMetadata(metadata)
                .dueAt(dueAt)
                .result(accepted)
                .failureReason("")
                .build());
        session.record(AuditEventType.ACTION_EXECUTION_DEFERRED, Map.of(
                "operationId", operationId,
                "metadata", metadata));
        return StageOutcome.CONTINUE;
    }

    private static StageOutcome completeExecution(
            ActionExecutionSession session,
            ActionExecutionResult result) {
        ActionExecutionResult safeResult = result == null
                ? ActionExecutionResult.failed("ACTION_RESULT_MISSING", "Action executor returned no result.")
                : result;
        if (!safeResult.terminal()) {
            safeResult = ActionExecutionResult.failed(
                    "NON_TERMINAL_COMPLETED_DISPATCH",
                    "A completed action dispatch must return a terminal result.");
        }
        ActionExecutionResult executionResult = safeResult;
        session.result(executionResult);
        session.updateRun(run -> run.toBuilder()
                .status(actionRunStatus(executionResult))
                .result(executionResult)
                .failureReason(executionResult.terminalSuccess() ? "" : executionResult.message())
                .build());
        AuditEventType eventType = switch (executionResult.status()) {
            case SUCCEEDED -> AuditEventType.ACTION_EXECUTION_COMPLETED;
            case CANCELLED -> AuditEventType.ACTION_EXECUTION_CANCELLED;
            case ACCEPTED -> AuditEventType.ACTION_EXECUTION_DEFERRED;
            case FAILED, DENIED, VALIDATION_FAILED, PENDING_APPROVAL -> AuditEventType.ACTION_EXECUTION_FAILED;
        };
        session.record(eventType, resultPayload(executionResult));
        return StageOutcome.CONTINUE;
    }

    private static Map<String, Object> resultPayload(ActionExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status().name());
        payload.put("code", result.code());
        payload.put("message", result.message());
        payload.put("output", result.output());
        return Map.copyOf(payload);
    }

    private static ActionExecutionResult completedAsyncResult(ActionExecutionResult result) {
        if (result == null) {
            return ActionExecutionResult.failed(
                    "ACTION_ASYNC_RESULT_MISSING",
                    "Asynchronous action completed without a result.");
        }
        if (!result.terminal()) {
            return ActionExecutionResult.failed(
                    "NON_TERMINAL_ASYNC_COMPLETION",
                    "Asynchronous completion must return a terminal result.");
        }
        return result;
    }

    private static ActionExecutionResult asyncFailure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException completionException
                && completionException.getCause() != null
                ? completionException.getCause()
                : failure;
        return ActionExecutionResult.failed("ACTION_ASYNC_EXECUTION_FAILED", failureMessage(cause));
    }

    static StageOutcome finalizeExecution(ActionExecutionSession session) {
        if (session.duplicateDecision() != null
                && session.duplicateDecision().type() == DuplicateActionDecisionType.ACCEPT) {
            if (session.result().terminal()) {
                session.duplicateActionPolicy().complete(
                        session.proposal(), session.context(), session.result());
            }
        }
        ActionRun current = session.run();
        ActionRunStatus desiredStatus = current.status().isTerminal()
                ? current.status()
                : actionRunStatus(session.result());
        if (current.status() != desiredStatus || !Objects.equals(current.result(), session.result())) {
            session.updateRun(run -> run.toBuilder()
                    .status(desiredStatus)
                    .result(session.result())
                    .build());
        }
        session.runAfterFinalize();
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

    private static ActionExecutionResult concurrentUpdateResult(ActionExecutionSession session) {
        return session.runStore().find(session.run().runId())
                .map(run -> run.result() == null
                        ? ActionExecutionResult.denied(
                                "ACTION_RUN_CONCURRENT_UPDATE",
                                "Action run changed while this transition was being committed.")
                        : run.result())
                .orElseGet(() -> ActionExecutionResult.denied(
                        "ACTION_RUN_CONCURRENT_UPDATE",
                        "Action run changed and could not be reloaded."));
    }

    private static ActionRunStatus actionRunStatus(ActionExecutionResult result) {
        return switch (result.status()) {
            case SUCCEEDED -> ActionRunStatus.SUCCEEDED;
            case FAILED -> ActionRunStatus.FAILED;
            case DENIED, VALIDATION_FAILED -> ActionRunStatus.DENIED;
            case PENDING_APPROVAL -> ActionRunStatus.WAITING_APPROVAL;
            case ACCEPTED -> ActionRunStatus.WAITING_EXTERNAL;
            case CANCELLED -> ActionRunStatus.CANCELLED;
        };
    }

    private static void bestEffortRuntimeFailureRunUpdate(ActionExecutionSession session, String message) {
        if (!session.runCreated()) {
            return;
        }
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
        if (!session.runCreated()) {
            return;
        }
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
            session.duplicateActionPolicy().release(session.proposal(), session.context(), cause);
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
