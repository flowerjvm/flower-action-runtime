package io.github.parkkevinsb.flower.agent.runtime;

import io.github.parkkevinsb.flower.agent.runtime.action.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionEffect;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionExecutor;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionRiskLevel;
import io.github.parkkevinsb.flower.agent.runtime.action.InMemoryActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.approval.ApprovalGate;
import io.github.parkkevinsb.flower.agent.runtime.audit.AuditEvent;
import io.github.parkkevinsb.flower.agent.runtime.audit.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.audit.AuditSink;
import io.github.parkkevinsb.flower.agent.runtime.audit.TraceSink;
import io.github.parkkevinsb.flower.agent.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.policy.PolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.validation.ActionInputValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private record StubExecutor(ActionDefinition definition, ActionExecutionResult result) implements ActionExecutor {
        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            return result;
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
