package io.github.flowerjvm.flower.action.runtime;

import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEvent;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEventType;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecisionType;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultActionRuntimeTest {
    @Test
    void executesRegisteredActionThroughPolicyAndAudit() {
        RecordingAuditSink audit = new RecordingAuditSink();
        ActionDefinition definition = definition("CreateReport", ActionEffect.WRITE, Set.of(ActionOrigin.USER));
        ActionExecutor executor = new StubExecutor(definition, ActionExecutionResult.succeeded(Map.of("reportId", 10)));
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                ActionInputValidator.allowAll(),
                PolicyGate.allowAll(),
                ApprovalGate.unsupported(),
                new InMemoryDuplicateActionPolicy(),
                audit,
                TraceSink.noop());

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of("siteId", 1), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(result.output()).containsEntry("reportId", 10);
        assertThat(audit.types()).containsSequence(
                AuditEventType.ACTION_PROPOSED,
                AuditEventType.ACTION_RESOLVED,
                AuditEventType.VALIDATION_COMPLETED,
                AuditEventType.POLICY_EVALUATED,
                AuditEventType.ACTION_EXECUTION_STARTED,
                AuditEventType.ACTION_EXECUTION_COMPLETED);
    }

    @Test
    void deniesUnknownActionBeforeExecution() {
        RecordingAuditSink audit = new RecordingAuditSink();
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of()),
                null,
                null,
                null,
                null,
                audit,
                null);

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("UnknownAction", Map.of(), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(result.message()).contains("not registered");
        assertThat(audit.types()).contains(AuditEventType.ACTION_DENIED);
    }

    @Test
    void requiresApprovalForAiPlannerWriteActionByDefault() {
        ActionDefinition definition = definition("UpdateReport", ActionEffect.WRITE, Set.of(ActionOrigin.AI_PLANNER));
        ActionExecutor executor = new StubExecutor(definition, ActionExecutionResult.succeeded(Map.of()));
        DefaultActionRuntime runtime = new DefaultActionRuntime(new InMemoryActionRegistry(List.of(executor)));

        ActionExecutionResult result = runtime.handle(
                new ActionProposal(
                        "proposal-1",
                        "UpdateReport",
                        ActionOrigin.AI_PLANNER,
                        "planner",
                        "update report",
                        0.8d,
                        Map.of(),
                        null,
                        Map.of()),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(result.output()).containsKey("approvalId");
    }

    @Test
    void returnsExistingResultForDuplicateIdempotencyKey() {
        ActionDefinition definition = definition("ReadStatus", ActionEffect.READ_ONLY, Set.of(ActionOrigin.USER));
        ActionExecutor executor = new StubExecutor(definition, ActionExecutionResult.succeeded(Map.of("status", "ok")));
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                null,
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null);
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");
        ActionProposal first = new ActionProposal(
                "proposal-1",
                "ReadStatus",
                ActionOrigin.USER,
                "user-1",
                "",
                1.0d,
                Map.of(),
                "same-key",
                Map.of());
        ActionProposal second = new ActionProposal(
                "proposal-2",
                "ReadStatus",
                ActionOrigin.USER,
                "user-1",
                "",
                1.0d,
                Map.of(),
                "same-key",
                Map.of());

        ActionExecutionResult firstResult = runtime.handle(first, context);
        ActionExecutionResult secondResult = runtime.handle(second, context);

        assertThat(firstResult.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(secondResult).isEqualTo(firstResult);
    }

    @Test
    void dryRunFailureStopsBeforeRealExecution() {
        ActionDefinition definition = dryRunnableDefinition("CreateReport", ActionEffect.WRITE, Set.of(ActionOrigin.USER));
        DryRunExecutor executor = new DryRunExecutor(
                definition,
                ActionExecutionResult.failed("dry run rejected"),
                ActionExecutionResult.succeeded(Map.of("reportId", 10)));
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                (proposal, action, context) ->
                        new PolicyDecision(PolicyDecisionType.REQUIRE_DRY_RUN, "dry run first", Map.of()),
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null);

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of(), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(result.message()).contains("dry run rejected");
        assertThat(executor.dryRunCalls()).isEqualTo(1);
        assertThat(executor.executeCalls()).isZero();
    }

    @Test
    void dryRunRequiredButUnsupportedIsDeniedBeforeExecution() {
        ActionDefinition definition = definition("CreateReport", ActionEffect.WRITE, Set.of(ActionOrigin.USER));
        DryRunExecutor executor = new DryRunExecutor(
                definition,
                ActionExecutionResult.succeeded(Map.of("ok", true)),
                ActionExecutionResult.succeeded(Map.of("reportId", 10)));
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                (proposal, action, context) ->
                        new PolicyDecision(PolicyDecisionType.REQUIRE_DRY_RUN, "dry run first", Map.of()),
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null);

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of(), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(result.message()).contains("Dry-run is required but not supported");
        assertThat(executor.dryRunCalls()).isZero();
        assertThat(executor.executeCalls()).isZero();
    }

    @Test
    void criticalRiskActionRequiresApprovalByDefault() {
        ActionDefinition definition = new ActionDefinition(
                "DeleteProject",
                "DeleteProject",
                "",
                ActionEffect.PRODUCTION_CHANGE,
                ActionRiskLevel.CRITICAL,
                Set.of(ActionOrigin.USER),
                Set.of(),
                false,
                false,
                true,
                "",
                "",
                Map.of());
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(new StubExecutor(
                        definition,
                        ActionExecutionResult.succeeded(Map.of())))));

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("DeleteProject", Map.of(), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
    }

    private static ActionDefinition definition(
            String actionId,
            ActionEffect effect,
            Set<ActionOrigin> allowedOrigins) {
        return new ActionDefinition(
                actionId,
                actionId,
                "",
                effect,
                ActionRiskLevel.MEDIUM,
                allowedOrigins,
                Set.of(),
                false,
                false,
                true,
                "",
                "",
                Map.of());
    }

    private static ActionDefinition dryRunnableDefinition(
            String actionId,
            ActionEffect effect,
            Set<ActionOrigin> allowedOrigins) {
        return new ActionDefinition(
                actionId,
                actionId,
                "",
                effect,
                ActionRiskLevel.MEDIUM,
                allowedOrigins,
                Set.of(),
                true,
                false,
                true,
                "",
                "",
                Map.of());
    }

    private record StubExecutor(ActionDefinition definition, ActionExecutionResult result) implements ActionExecutor {
        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            return result;
        }
    }

    private static final class DryRunExecutor implements ActionExecutor {
        private final ActionDefinition definition;
        private final ActionExecutionResult dryRunResult;
        private final ActionExecutionResult executeResult;
        private final AtomicInteger dryRunCalls = new AtomicInteger();
        private final AtomicInteger executeCalls = new AtomicInteger();

        private DryRunExecutor(
                ActionDefinition definition,
                ActionExecutionResult dryRunResult,
                ActionExecutionResult executeResult) {
            this.definition = definition;
            this.dryRunResult = dryRunResult;
            this.executeResult = executeResult;
        }

        @Override
        public ActionDefinition definition() {
            return definition;
        }

        @Override
        public ActionExecutionResult dryRun(ActionExecutionContext context) {
            dryRunCalls.incrementAndGet();
            return dryRunResult;
        }

        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            executeCalls.incrementAndGet();
            return executeResult;
        }

        int dryRunCalls() {
            return dryRunCalls.get();
        }

        int executeCalls() {
            return executeCalls.get();
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
