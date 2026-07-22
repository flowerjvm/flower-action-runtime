package io.github.flowerjvm.flower.action.runtime;

import io.github.flowerjvm.flower.action.runtime.action.ActionRegistry;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.AsyncActionExecutor;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.pipeline.ActionExecutionSession;
import io.github.flowerjvm.flower.action.runtime.pipeline.ActionPipeline;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Direct reference action runtime.
 *
 * <p>It runs the shared {@link ActionPipeline} initiation stages in-thread and acts as the reference backend for
 * controlled-action semantics. An executor may still return an asynchronous or deferred dispatch.</p>
 */
public final class DefaultActionRuntime implements CompletableActionRuntime {
    private final ActionRegistry registry;
    private final ActionInputValidator inputValidator;
    private final PolicyGate policyGate;
    private final PreExecutionGuard preExecutionGuard;
    private final ApprovalGate approvalGate;
    private final DuplicateActionPolicy duplicateActionPolicy;
    private final AuditSink auditSink;
    private final TraceSink traceSink;
    private final RunStore runStore;
    private final ConcurrentMap<String, Object> runLocks = new ConcurrentHashMap<>();

    public DefaultActionRuntime(
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink) {
        this(registry, inputValidator, policyGate, approvalGate, duplicateActionPolicy, auditSink, traceSink,
                RunStore.noop());
    }

    public DefaultActionRuntime(
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink,
            RunStore runStore) {
        this(
                registry,
                inputValidator,
                policyGate,
                approvalGate,
                duplicateActionPolicy,
                auditSink,
                traceSink,
                runStore,
                PreExecutionGuard.allowAll());
    }

    public DefaultActionRuntime(
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink,
            RunStore runStore,
            PreExecutionGuard preExecutionGuard) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.inputValidator = inputValidator == null ? ActionInputValidator.allowAll() : inputValidator;
        this.policyGate = policyGate == null ? new DefaultPolicyGate() : policyGate;
        this.preExecutionGuard = preExecutionGuard == null ? PreExecutionGuard.allowAll() : preExecutionGuard;
        this.approvalGate = approvalGate == null ? ApprovalGate.unsupported() : approvalGate;
        this.duplicateActionPolicy = duplicateActionPolicy == null
                ? DuplicateActionPolicy.acceptAll()
                : duplicateActionPolicy;
        this.auditSink = auditSink == null ? AuditSink.noop() : auditSink;
        this.traceSink = traceSink == null ? TraceSink.noop() : traceSink;
        this.runStore = runStore == null ? RunStore.noop() : runStore;
    }

    public DefaultActionRuntime(ActionRegistry registry) {
        this(registry, null, null, null, null, null, null);
    }

    @Override
    public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(context, "context must not be null");
        ActionExecutionSession session = new ActionExecutionSession(
                proposal,
                context,
                registry,
                inputValidator,
                policyGate,
                approvalGate,
                duplicateActionPolicy,
                auditSink,
                traceSink,
                runStore,
                preExecutionGuard,
                this::complete);
        return ActionPipeline.run(session);
    }

    @Override
    public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        String normalizedRunId = runId == null ? "" : runId.trim();
        return withRunLock(normalizedRunId, () -> resumeLocked(normalizedRunId, decision));
    }

    @Override
    public ActionExecutionResult complete(
            String runId,
            String attemptToken,
            ActionExecutionResult result) {
        String normalizedRunId = runId == null ? "" : runId.trim();
        String normalizedAttemptToken = attemptToken == null ? "" : attemptToken.trim();
        return withRunLock(
                normalizedRunId,
                () -> completeLocked(normalizedRunId, normalizedAttemptToken, result));
    }

    @Override
    public ActionExecutionResult cancel(String runId, String reason) {
        String normalizedRunId = runId == null ? "" : runId.trim();
        String normalizedReason = reason == null ? "" : reason.trim();
        return withRunLock(normalizedRunId, () -> cancelLocked(normalizedRunId, normalizedReason));
    }

    private <T> T withRunLock(String runId, Supplier<T> action) {
        while (true) {
            Object lock = runLocks.computeIfAbsent(runId, ignored -> new Object());
            synchronized (lock) {
                if (runLocks.get(runId) != lock) {
                    continue;
                }
                try {
                    return action.get();
                } finally {
                    runLocks.remove(runId, lock);
                }
            }
        }
    }

    private ActionExecutionResult resumeLocked(String normalizedRunId, ApprovalDecision decision) {
        ActionRun run = runStore.find(normalizedRunId).orElse(null);
        if (run == null) {
            return ActionExecutionResult.denied("ACTION_RUN_NOT_FOUND", "Unknown run: " + normalizedRunId);
        }
        if (run.status().isTerminal()) {
            return run.result() != null
                    ? run.result()
                    : ActionExecutionResult.failed(
                            "TERMINAL_RUN_RESULT_MISSING",
                            "Run already terminal without result");
        }
        if (run.status() != ActionRunStatus.WAITING_APPROVAL) {
            return ActionExecutionResult.denied(
                    "ACTION_RUN_NOT_WAITING_APPROVAL",
                    "Run is not awaiting approval: " + normalizedRunId);
        }
        if (!run.approvalId().equals(decision.approvalId())) {
            return ActionExecutionResult.denied(
                    "APPROVAL_ID_MISMATCH",
                    "Approval id mismatch for run: " + normalizedRunId);
        }

        ActionProposal proposal = proposalFromRun(run);
        ExecutionContext resumeContext = contextFromRun(run);
        ActionExecutionSession session = ActionExecutionSession.forResume(
                run,
                proposal,
                resumeContext,
                registry,
                inputValidator,
                policyGate,
                approvalGate,
                duplicateActionPolicy,
                auditSink,
                traceSink,
                runStore,
                preExecutionGuard,
                this::complete);
        return ActionPipeline.resume(session, decision);
    }

    private ActionExecutionResult completeLocked(
            String runId,
            String attemptToken,
            ActionExecutionResult result) {
        ActionRun run = runStore.find(runId).orElse(null);
        if (run == null) {
            return ActionExecutionResult.denied("ACTION_RUN_NOT_FOUND", "Unknown run: " + runId);
        }
        if (run.status().isTerminal()) {
            return storedTerminalResult(run);
        }
        if (run.status() != ActionRunStatus.WAITING_EXTERNAL) {
            return ActionExecutionResult.denied(
                    "ACTION_RUN_NOT_WAITING_EXTERNAL",
                    "Run is not awaiting external completion: " + runId);
        }
        if (attemptToken.isBlank() || !run.attemptToken().equals(attemptToken)) {
            return ActionExecutionResult.denied(
                    "STALE_ACTION_ATTEMPT",
                    "Attempt token mismatch for run: " + runId);
        }
        if (result != null && !result.terminal()) {
            return ActionExecutionResult.denied(
                    "NON_TERMINAL_COMPLETION_RESULT",
                    "Deferred completion requires a terminal result: " + runId);
        }
        return ActionPipeline.completeDeferred(sessionFromRun(run), result);
    }

    private ActionExecutionResult cancelLocked(String runId, String reason) {
        ActionRun run = runStore.find(runId).orElse(null);
        if (run == null) {
            return ActionExecutionResult.denied("ACTION_RUN_NOT_FOUND", "Unknown run: " + runId);
        }
        if (run.status().isTerminal()) {
            return storedTerminalResult(run);
        }
        if (run.status() == ActionRunStatus.RUNNING) {
            return new ActionExecutionResult(
                    ActionExecutionStatus.DENIED,
                    "ACTION_RUN_NOT_CANCELLABLE_WHILE_RUNNING",
                    "An action that is dispatching or already running cannot be safely cancelled: " + runId,
                    Map.of(),
                    RetryDisposition.MANUAL_REVIEW);
        }
        if (run.status() == ActionRunStatus.WAITING_EXTERNAL) {
            String cancellationWarning = cancelExternalOperation(run, reason);
            if (!cancellationWarning.isBlank()) {
                String message = reason.isBlank() ? "Action run was cancelled." : reason;
                return ActionPipeline.completeDeferred(
                        sessionFromRun(run),
                        new ActionExecutionResult(
                                ActionExecutionStatus.CANCELLED,
                                "ACTION_CANCELLED_EXTERNAL_CANCEL_FAILED",
                                message,
                                Map.of("cancellationWarning", cancellationWarning),
                                RetryDisposition.MANUAL_REVIEW));
            }
        }
        String message = reason.isBlank() ? "Action run was cancelled." : reason;
        return ActionPipeline.completeDeferred(
                sessionFromRun(run),
                ActionExecutionResult.cancelled("ACTION_CANCELLED", message));
    }

    private String cancelExternalOperation(ActionRun run, String reason) {
        try {
            registry.findExecutor(run.actionId()).ifPresent(executor -> {
                if (executor instanceof DeferredActionExecutor deferredExecutor) {
                    deferredExecutor.cancel(
                            new ActionExecutionContext(
                                    contextFromRun(run),
                                    proposalFromRun(run),
                                    executor.definition(),
                                    run.input(),
                                    run.attemptToken()),
                            run.externalOperationId(),
                            reason);
                }
                if (executor instanceof AsyncActionExecutor asyncExecutor) {
                    asyncExecutor.cancel(
                            new ActionExecutionContext(
                                    contextFromRun(run),
                                    proposalFromRun(run),
                                    executor.definition(),
                                    run.input(),
                                    run.attemptToken()),
                            reason);
                }
            });
            return "";
        } catch (RuntimeException exception) {
            return exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
        }
    }

    private ActionExecutionSession sessionFromRun(ActionRun run) {
        return ActionExecutionSession.forResume(
                run,
                proposalFromRun(run),
                contextFromRun(run),
                registry,
                inputValidator,
                policyGate,
                approvalGate,
                duplicateActionPolicy,
                auditSink,
                traceSink,
                runStore,
                preExecutionGuard,
                this::complete);
    }

    private static ActionExecutionResult storedTerminalResult(ActionRun run) {
        return run.result() != null
                ? run.result()
                : ActionExecutionResult.failed(
                        "TERMINAL_RUN_RESULT_MISSING",
                        "Run already terminal without result");
    }

    private static ActionProposal proposalFromRun(ActionRun run) {
        return new ActionProposal(
                run.proposalId(),
                run.actionId(),
                run.requestChannel(),
                run.proposerType(),
                run.requesterId(),
                run.proposalReason(),
                run.proposalConfidence(),
                run.input(),
                run.duplicateKey(),
                run.proposalMetadata());
    }

    private static ExecutionContext contextFromRun(ActionRun run) {
        return new ExecutionContext(run.tenantId(), run.userId(), run.runId(), run.traceId(), run.contextMetadata());
    }
}
