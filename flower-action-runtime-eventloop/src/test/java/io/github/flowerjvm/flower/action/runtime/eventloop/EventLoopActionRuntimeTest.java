package io.github.flowerjvm.flower.action.runtime.eventloop;

import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.ActionOrigin;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEvent;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEventType;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import io.github.flowerjvm.flower.core.event.InMemoryEventBus;
import io.github.flowerjvm.flower.core.time.ManualClock;
import io.github.flowerjvm.flower.eventloop.worker.EventWorker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EventLoopActionRuntimeTest {
    @Test
    void approvalSignalExecutesParkedAction() {
        Fixture fixture = fixture(1_000L, 100L);

        ActionExecutionResult parked = fixture.runtime.handle(
                aiProposal("proposal-approve", "UpdateReport"),
                context("run-event-approve"));
        ActionRun waiting = fixture.runStore.find("run-event-approve").orElseThrow();

        assertThat(parked.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(waiting.status()).isEqualTo(ActionRunStatus.WAITING_APPROVAL);
        assertThat(waiting.dueAt()).isEqualTo(Instant.ofEpochMilli(1_100L));
        assertThat(fixture.runtime.worker().activeCount()).isEqualTo(1);
        assertThat(fixture.executor.calls()).isZero();

        fixture.runtime.signalApproval(ApprovalDecision.approved(waiting.approvalId(), "admin"));
        fixture.runtime.drain();

        ActionRun completed = fixture.runStore.find("run-event-approve").orElseThrow();
        assertThat(completed.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(completed.result().status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(fixture.executor.calls()).isEqualTo(1);
        assertThat(fixture.runtime.worker().activeCount()).isZero();
    }

    @Test
    void rejectionSignalDeniesParkedActionWithoutExecution() {
        Fixture fixture = fixture(1_000L, 100L);

        fixture.runtime.handle(aiProposal("proposal-reject", "UpdateReport"), context("run-event-reject"));
        ActionRun waiting = fixture.runStore.find("run-event-reject").orElseThrow();

        fixture.runtime.signalApproval(ApprovalDecision.rejected(waiting.approvalId(), "admin", "no"));
        fixture.runtime.drain();

        ActionRun completed = fixture.runStore.find("run-event-reject").orElseThrow();
        assertThat(completed.status()).isEqualTo(ActionRunStatus.DENIED);
        assertThat(completed.result().status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(completed.failureReason()).contains("no");
        assertThat(fixture.executor.calls()).isZero();
    }

    @Test
    void approvalDeadlineExpiresParkedAction() {
        Fixture fixture = fixture(1_000L, 100L);

        fixture.runtime.handle(aiProposal("proposal-expire", "UpdateReport"), context("run-event-expire"));
        ActionRun waiting = fixture.runStore.find("run-event-expire").orElseThrow();

        fixture.clock.advance(101L);
        fixture.runtime.drain();

        ActionRun completed = fixture.runStore.find("run-event-expire").orElseThrow();
        assertThat(waiting.dueAt()).isEqualTo(Instant.ofEpochMilli(1_100L));
        assertThat(completed.status()).isEqualTo(ActionRunStatus.EXPIRED);
        assertThat(completed.result().status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(completed.failureReason()).isEqualTo("Approval expired");
        assertThat(fixture.executor.calls()).isZero();
        assertThat(fixture.audit.types()).contains(AuditEventType.APPROVAL_EXPIRED);
        assertThat(fixture.runtime.worker().activeCount()).isZero();
    }

    @Test
    void recoverParkedReRegistersAwaitAndCanApproveAfterRestart() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        ManualClock clock = new ManualClock(1_000L);
        Fixture first = fixture(runStore, clock, 100L);

        first.runtime.handle(aiProposal("proposal-recover", "UpdateReport"), context("run-event-recover"));
        ActionRun waiting = runStore.find("run-event-recover").orElseThrow();
        assertThat(first.executor.calls()).isZero();

        Fixture restarted = fixture(runStore, clock, 100L);
        assertThat(restarted.runtime.recoverParked("tenant-1")).isEqualTo(1);
        assertThat(restarted.runtime.worker().activeCount()).isEqualTo(1);

        restarted.runtime.signalApproval(ApprovalDecision.approved(waiting.approvalId(), "admin"));
        restarted.runtime.drain();

        ActionRun completed = runStore.find("run-event-recover").orElseThrow();
        assertThat(completed.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(restarted.executor.calls()).isEqualTo(1);
    }

    @Test
    void duplicateApprovalSignalsExecuteOnlyOnce() {
        Fixture fixture = fixture(1_000L, 100L);

        fixture.runtime.handle(aiProposal("proposal-duplicate", "UpdateReport"), context("run-event-duplicate"));
        ActionRun waiting = fixture.runStore.find("run-event-duplicate").orElseThrow();
        ApprovalDecision decision = ApprovalDecision.approved(waiting.approvalId(), "admin");

        fixture.runtime.signalApproval(decision);
        fixture.runtime.signalApproval(decision);
        fixture.runtime.drain();

        ActionRun completed = fixture.runStore.find("run-event-duplicate").orElseThrow();
        assertThat(completed.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(fixture.executor.calls()).isEqualTo(1);
    }

    @Test
    void approvalSignalRecordsApprovalApprovedAudit() {
        Fixture fixture = fixture(1_000L, 100L);

        fixture.runtime.handle(aiProposal("proposal-audit", "UpdateReport"), context("run-event-audit"));
        ActionRun waiting = fixture.runStore.find("run-event-audit").orElseThrow();

        fixture.runtime.signalApproval(ApprovalDecision.approved(waiting.approvalId(), "admin"));
        fixture.runtime.drain();

        assertThat(fixture.audit.types()).contains(AuditEventType.APPROVAL_APPROVED);
    }

    private static Fixture fixture(long startMillis, long approvalTimeoutMillis) {
        return fixture(new InMemoryRunStore(), new ManualClock(startMillis), approvalTimeoutMillis);
    }

    private static Fixture fixture(InMemoryRunStore runStore, ManualClock clock, long approvalTimeoutMillis) {
        RecordingAuditSink audit = new RecordingAuditSink();
        CountingExecutor executor = new CountingExecutor(writeAction("UpdateReport"),
                ActionExecutionResult.succeeded(Map.of("updated", true)));
        EventWorker worker = EventWorker.builder("approval-worker")
                .clock(clock)
                .eventBus(InMemoryEventBus.create())
                .build();
        EventLoopActionRuntime runtime = EventLoopActionRuntime.create(
                new InMemoryActionRegistry(List.of(executor)),
                ActionInputValidator.allowAll(),
                new DefaultPolicyGate(),
                null,
                new InMemoryDuplicateActionPolicy(),
                audit,
                null,
                runStore,
                worker,
                clock,
                approvalTimeoutMillis);
        return new Fixture(runtime, runStore, clock, executor, audit);
    }

    private static ActionProposal aiProposal(String proposalId, String actionId) {
        return new ActionProposal(
                proposalId,
                actionId,
                ActionOrigin.AI_PLANNER,
                "planner",
                "update",
                0.9d,
                Map.of("siteId", 1),
                null,
                Map.of());
    }

    private static ExecutionContext context(String runId) {
        return new ExecutionContext("tenant-1", "user-1", runId, runId + "-trace", Map.of());
    }

    private static ActionDefinition writeAction(String actionId) {
        return new ActionDefinition(actionId, actionId, "", ActionEffect.WRITE, ActionRiskLevel.MEDIUM,
                Set.of(ActionOrigin.AI_PLANNER), Set.of(), false, false, true, "", "", Map.of());
    }

    private record Fixture(
            EventLoopActionRuntime runtime,
            InMemoryRunStore runStore,
            ManualClock clock,
            CountingExecutor executor,
            RecordingAuditSink audit) {
    }

    private static final class CountingExecutor implements ActionExecutor {
        private final ActionDefinition definition;
        private final ActionExecutionResult result;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingExecutor(ActionDefinition definition, ActionExecutionResult result) {
            this.definition = definition;
            this.result = result;
        }

        @Override
        public ActionDefinition definition() {
            return definition;
        }

        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            calls.incrementAndGet();
            return result;
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class RecordingAuditSink implements AuditSink {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        List<AuditEventType> types() {
            return events.stream().map(AuditEvent::type).toList();
        }
    }
}
