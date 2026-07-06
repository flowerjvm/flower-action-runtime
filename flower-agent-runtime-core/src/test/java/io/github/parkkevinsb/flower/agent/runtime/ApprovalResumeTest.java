package io.github.parkkevinsb.flower.agent.runtime;

import io.github.parkkevinsb.flower.agent.runtime.action.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionEffect;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionExecutor;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionRiskLevel;
import io.github.parkkevinsb.flower.agent.runtime.action.InMemoryActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.approval.ApprovalDecision;
import io.github.parkkevinsb.flower.agent.runtime.audit.AuditEvent;
import io.github.parkkevinsb.flower.agent.runtime.audit.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.audit.AuditSink;
import io.github.parkkevinsb.flower.agent.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.run.ActionRun;
import io.github.parkkevinsb.flower.agent.runtime.run.ActionRunStatus;
import io.github.parkkevinsb.flower.agent.runtime.run.InMemoryRunStore;
import io.github.parkkevinsb.flower.agent.runtime.run.RunStore;
import io.github.parkkevinsb.flower.agent.runtime.validation.ActionInputValidator;
import io.github.parkkevinsb.flower.agent.runtime.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalResumeTest {
    @Test
    void approvesParkedRunAndExecutesActionOnce() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        CountingExecutor executor = new CountingExecutor(writeAction("UpdateReport"),
                ActionExecutionResult.succeeded(Map.of("updated", true)));
        DefaultActionRuntime runtime = runtime(executor, ActionInputValidator.allowAll(), runStore, null);
        ExecutionContext context = context("run-approve");

        ActionExecutionResult parked = runtime.handle(aiProposal("proposal-approve", "UpdateReport"), context);
        ActionRun waiting = runStore.find(context.runId()).orElseThrow();

        ActionExecutionResult resumed = runtime.resume(
                context.runId(),
                ApprovalDecision.approved(waiting.approvalId(), "admin"));

        ActionRun completed = runStore.find(context.runId()).orElseThrow();
        assertThat(parked.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(waiting.status()).isEqualTo(ActionRunStatus.WAITING_APPROVAL);
        assertThat(resumed.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(completed.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(completed.result()).isEqualTo(resumed);
        assertThat(executor.calls()).isEqualTo(1);
    }

    @Test
    void rejectsParkedRunWithoutExecutingAction() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        RecordingAuditSink audit = new RecordingAuditSink();
        CountingExecutor executor = new CountingExecutor(writeAction("UpdateReport"),
                ActionExecutionResult.succeeded(Map.of()));
        DefaultActionRuntime runtime = runtime(executor, ActionInputValidator.allowAll(), runStore, audit);
        ExecutionContext context = context("run-reject");

        runtime.handle(aiProposal("proposal-reject", "UpdateReport"), context);
        ActionRun waiting = runStore.find(context.runId()).orElseThrow();

        ActionExecutionResult rejected = runtime.resume(
                context.runId(),
                ApprovalDecision.rejected(waiting.approvalId(), "admin", "not allowed"));

        ActionRun completed = runStore.find(context.runId()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(rejected.message()).contains("rejected").contains("not allowed");
        assertThat(completed.status()).isEqualTo(ActionRunStatus.DENIED);
        assertThat(completed.failureReason()).contains("not allowed");
        assertThat(executor.calls()).isZero();
        assertThat(audit.types()).contains(AuditEventType.APPROVAL_REJECTED);
    }

    @Test
    void secondApprovalCallbackReturnsStoredResultWithoutReexecution() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        CountingExecutor executor = new CountingExecutor(writeAction("UpdateReport"),
                ActionExecutionResult.succeeded(Map.of("updated", true)));
        DefaultActionRuntime runtime = runtime(executor, ActionInputValidator.allowAll(), runStore, null);
        ExecutionContext context = context("run-idempotent-approval");

        runtime.handle(aiProposal("proposal-idempotent", "UpdateReport"), context);
        ActionRun waiting = runStore.find(context.runId()).orElseThrow();
        ApprovalDecision decision = ApprovalDecision.approved(waiting.approvalId(), "admin");

        ActionExecutionResult first = runtime.resume(context.runId(), decision);
        ActionExecutionResult second = runtime.resume(context.runId(), decision);

        assertThat(first.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(second).isEqualTo(first);
        assertThat(executor.calls()).isEqualTo(1);
    }

    @Test
    void guardsUnknownNonWaitingAndMismatchedApprovalRuns() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        CountingExecutor executor = new CountingExecutor(writeAction("UpdateReport"),
                ActionExecutionResult.succeeded(Map.of()));
        DefaultActionRuntime runtime = runtime(executor, ActionInputValidator.allowAll(), runStore, null);

        assertThat(runtime.resume("missing-run", ApprovalDecision.approved("approval-1", "admin")).status())
                .isEqualTo(ActionExecutionStatus.DENIED);

        ActionRun running = ActionRun.requested(
                aiProposal("proposal-running", "UpdateReport"),
                context("run-running")).toBuilder()
                .status(ActionRunStatus.RUNNING)
                .approvalId("approval-running")
                .build();
        runStore.create(running);
        assertThat(runtime.resume(running.runId(), ApprovalDecision.approved("approval-running", "admin")).status())
                .isEqualTo(ActionExecutionStatus.DENIED);

        ExecutionContext waitingContext = context("run-mismatch");
        runtime.handle(aiProposal("proposal-mismatch", "UpdateReport"), waitingContext);
        assertThat(runtime.resume(waitingContext.runId(), ApprovalDecision.approved("wrong-approval", "admin"))
                .status()).isEqualTo(ActionExecutionStatus.DENIED);
    }

    @Test
    void resumeRevalidatesInputBeforeExecution() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AtomicBoolean rejectOnResume = new AtomicBoolean(false);
        ActionInputValidator validator = (proposal, definition, context) -> rejectOnResume.get()
                ? ValidationResult.invalid("input expired")
                : ValidationResult.ok();
        CountingExecutor executor = new CountingExecutor(writeAction("UpdateReport"),
                ActionExecutionResult.succeeded(Map.of()));
        DefaultActionRuntime runtime = runtime(executor, validator, runStore, null);
        ExecutionContext context = context("run-revalidate");

        runtime.handle(aiProposal("proposal-revalidate", "UpdateReport"), context);
        ActionRun waiting = runStore.find(context.runId()).orElseThrow();
        rejectOnResume.set(true);

        ActionExecutionResult result = runtime.resume(
                context.runId(),
                ApprovalDecision.approved(waiting.approvalId(), "admin"));

        ActionRun completed = runStore.find(context.runId()).orElseThrow();
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.VALIDATION_FAILED);
        assertThat(completed.status()).isEqualTo(ActionRunStatus.DENIED);
        assertThat(completed.failureReason()).contains("input expired");
        assertThat(executor.calls()).isZero();
    }

    @Test
    void recordsApprovalApprovedAuditEvent() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        RecordingAuditSink audit = new RecordingAuditSink();
        CountingExecutor executor = new CountingExecutor(writeAction("UpdateReport"),
                ActionExecutionResult.succeeded(Map.of()));
        DefaultActionRuntime runtime = runtime(executor, ActionInputValidator.allowAll(), runStore, audit);
        ExecutionContext context = context("run-approval-audit");

        runtime.handle(aiProposal("proposal-audit", "UpdateReport"), context);
        ActionRun waiting = runStore.find(context.runId()).orElseThrow();

        runtime.resume(context.runId(), ApprovalDecision.approved(waiting.approvalId(), "admin"));

        assertThat(audit.types()).contains(AuditEventType.APPROVAL_APPROVED);
    }

    private static DefaultActionRuntime runtime(
            ActionExecutor executor,
            ActionInputValidator validator,
            InMemoryRunStore runStore,
            AuditSink auditSink) {
        return new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                validator,
                null,
                null,
                new InMemoryDuplicateActionPolicy(),
                auditSink,
                null,
                runStore);
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
