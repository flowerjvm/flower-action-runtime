package io.github.parkkevinsb.flower.agent.runtime;

import io.github.parkkevinsb.flower.agent.runtime.action.ActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.approval.ApprovalDecision;
import io.github.parkkevinsb.flower.agent.runtime.approval.ApprovalGate;
import io.github.parkkevinsb.flower.agent.runtime.audit.AuditSink;
import io.github.parkkevinsb.flower.agent.runtime.audit.TraceSink;
import io.github.parkkevinsb.flower.agent.runtime.duplicate.DuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.pipeline.ActionExecutionSession;
import io.github.parkkevinsb.flower.agent.runtime.pipeline.ActionPipeline;
import io.github.parkkevinsb.flower.agent.runtime.policy.DefaultPolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.policy.PolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.run.ActionRun;
import io.github.parkkevinsb.flower.agent.runtime.run.ActionRunStatus;
import io.github.parkkevinsb.flower.agent.runtime.run.RunStore;
import io.github.parkkevinsb.flower.agent.runtime.validation.ActionInputValidator;
import java.util.Map;
import java.util.Objects;

/**
 * Direct, synchronous action runtime.
 *
 * <p>It runs the shared {@link ActionPipeline} stages in-thread and acts as the reference backend for
 * controlled-action semantics.</p>
 */
public final class DefaultActionRuntime implements ResumableActionRuntime {
    private final ActionRegistry registry;
    private final ActionInputValidator inputValidator;
    private final PolicyGate policyGate;
    private final ApprovalGate approvalGate;
    private final DuplicateActionPolicy duplicateActionPolicy;
    private final AuditSink auditSink;
    private final TraceSink traceSink;
    private final RunStore runStore;

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
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.inputValidator = inputValidator == null ? ActionInputValidator.allowAll() : inputValidator;
        this.policyGate = policyGate == null ? new DefaultPolicyGate() : policyGate;
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
                runStore);
        return ActionPipeline.run(session);
    }

    @Override
    public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        String normalizedRunId = runId == null ? "" : runId.trim();
        ActionRun run = runStore.find(normalizedRunId).orElse(null);
        if (run == null) {
            return ActionExecutionResult.denied("Unknown run: " + normalizedRunId);
        }
        if (run.status().isTerminal()) {
            return run.result() != null
                    ? run.result()
                    : ActionExecutionResult.failed("Run already terminal without result");
        }
        if (run.status() != ActionRunStatus.WAITING_APPROVAL) {
            return ActionExecutionResult.denied("Run is not awaiting approval: " + normalizedRunId);
        }
        if (!run.approvalId().equals(decision.approvalId())) {
            return ActionExecutionResult.denied("Approval id mismatch for run: " + normalizedRunId);
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
                runStore);
        return ActionPipeline.resume(session, decision);
    }

    private static ActionProposal proposalFromRun(ActionRun run) {
        return new ActionProposal(
                run.proposalId(),
                run.actionId(),
                run.origin(),
                run.requesterId(),
                "",
                1.0d,
                run.input(),
                run.duplicateKey(),
                Map.of());
    }

    private static ExecutionContext contextFromRun(ActionRun run) {
        return new ExecutionContext(run.tenantId(), run.userId(), run.runId(), run.traceId(), Map.of());
    }
}
