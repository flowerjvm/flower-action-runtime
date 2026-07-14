package io.github.flowerjvm.flower.action.runtime.eventloop;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.action.ActionRegistry;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.core.context.ExecutionContext.Builder;
import io.github.flowerjvm.flower.core.time.Clock;
import io.github.flowerjvm.flower.core.worker.DuplicatePolicy;
import io.github.flowerjvm.flower.eventloop.flow.EventFlow;
import io.github.flowerjvm.flower.eventloop.worker.EventWorker;

import java.time.Instant;
import java.util.Objects;

public final class EventLoopActionRuntime implements ActionRuntime {
    public static final String FLOW_TYPE = "agent-approval";
    public static final String APPROVAL_SIGNAL = "approval";
    private static final long DEFAULT_APPROVAL_TIMEOUT_MILLIS = 300_000L;

    private final DefaultActionRuntime delegate;
    private final RunStore runStore;
    private final EventWorker worker;
    private final Clock clock;
    private final long approvalTimeoutMillis;

    private EventLoopActionRuntime(
            DefaultActionRuntime delegate,
            RunStore runStore,
            EventWorker worker,
            Clock clock,
            long approvalTimeoutMillis) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.runStore = Objects.requireNonNull(runStore, "runStore must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.approvalTimeoutMillis = approvalTimeoutMillis < 0 ? DEFAULT_APPROVAL_TIMEOUT_MILLIS : approvalTimeoutMillis;
    }

    public static EventLoopActionRuntime create(
            ActionRegistry registry,
            ActionInputValidator validator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicatePolicy,
            AuditSink auditSink,
            TraceSink traceSink,
            RunStore runStore,
            EventWorker worker,
            Clock clock,
            long approvalTimeoutMillis) {
        RunStore effectiveRunStore = Objects.requireNonNull(runStore, "runStore must not be null");
        DefaultActionRuntime delegate = new DefaultActionRuntime(
                registry,
                validator,
                policyGate,
                approvalGate,
                duplicatePolicy,
                auditSink,
                traceSink,
                effectiveRunStore);
        return new EventLoopActionRuntime(delegate, effectiveRunStore, worker, clock, approvalTimeoutMillis);
    }

    @Override
    public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(context, "context must not be null");
        ActionExecutionResult result = delegate.handle(proposal, context);
        if (result.status() != ActionExecutionStatus.PENDING_APPROVAL) {
            return result;
        }

        ActionRun run = runStore.find(context.runId())
                .orElseThrow(() -> new IllegalStateException(
                        "PENDING_APPROVAL result was returned without a persisted ActionRun: " + context.runId()));
        ActionRun parked = ensureDueAt(run);
        park(parked.runId(), parked.approvalId(), remainingMillis(parked), parked.tenantId());
        drainIfManual();
        return result;
    }

    public void signalApproval(ApprovalDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        worker.signal(APPROVAL_SIGNAL, decision.approvalId(), decision);
        drainIfManual();
    }

    public int recoverParked(String tenantId) {
        String normalizedTenantId = tenantId == null ? "" : tenantId.trim();
        int count = 0;
        for (ActionRun run : runStore.findResumable(normalizedTenantId)) {
            if (run.status() != ActionRunStatus.WAITING_APPROVAL) {
                continue;
            }
            ActionRun parked = ensureDueAt(run);
            park(parked.runId(), parked.approvalId(), remainingMillis(parked), parked.tenantId());
            count++;
        }
        drainIfManual();
        return count;
    }

    public void drain() {
        worker.drain();
    }

    public EventWorker worker() {
        return worker;
    }

    private ActionRun ensureDueAt(ActionRun run) {
        if (run.dueAt() != null) {
            return run;
        }
        ActionRun updated = run.toBuilder()
                .dueAt(Instant.ofEpochMilli(clock.currentTimeMillis() + approvalTimeoutMillis))
                .build();
        runStore.update(updated);
        return updated;
    }

    private long remainingMillis(ActionRun run) {
        long dueMillis = run.dueAt() == null
                ? clock.currentTimeMillis() + approvalTimeoutMillis
                : run.dueAt().toEpochMilli();
        return Math.max(0L, dueMillis - clock.currentTimeMillis());
    }

    private void park(String runId, String approvalId, long remainingMillis, String tenantId) {
        EventFlow flow = EventFlow.builder(FLOW_TYPE, runId)
                .executionContext(flowerContext(runId, tenantId))
                .step("await-approval", new ApprovalWaitStep(runId, approvalId, remainingMillis, delegate))
                .build();
        worker.submit(flow, DuplicatePolicy.IGNORE);
    }

    private void drainIfManual() {
        try {
            worker.drain();
        } catch (IllegalStateException ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains("background mode")) {
                throw ex;
            }
        }
    }

    private static io.github.flowerjvm.flower.core.context.ExecutionContext flowerContext(
            String runId,
            String tenantId) {
        Builder builder = io.github.flowerjvm.flower.core.context.ExecutionContext.builder()
                .runId(emptyToNull(runId))
                .traceId(emptyToNull(runId))
                .correlationId(emptyToNull(runId))
                .tenantId(emptyToNull(tenantId));
        return builder.build();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
