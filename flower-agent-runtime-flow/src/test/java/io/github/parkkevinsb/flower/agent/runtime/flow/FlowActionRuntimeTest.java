package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.ActionEffect;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionStatus;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutor;
import io.github.parkkevinsb.flower.agent.runtime.ActionOrigin;
import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ActionRiskLevel;
import io.github.parkkevinsb.flower.agent.runtime.AuditEvent;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.AuditSink;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.InMemoryActionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlowActionRuntimeTest {
    @Test
    void executesRegisteredActionThroughFlowerFlow() {
        RecordingAuditSink audit = new RecordingAuditSink();
        ActionDefinition definition = definition("CreateReport", ActionEffect.WRITE, Set.of(ActionOrigin.USER));
        FlowActionRuntime runtime = new FlowActionRuntime(
                new InMemoryActionRegistry(List.of(new StubExecutor(
                        definition,
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))))),
                null,
                null,
                null,
                null,
                audit,
                null,
                null,
                null,
                32);

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of("siteId", 1), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(audit.types()).containsSequence(
                AuditEventType.ACTION_PROPOSED,
                AuditEventType.ACTION_RESOLVED,
                AuditEventType.VALIDATION_COMPLETED,
                AuditEventType.POLICY_EVALUATED,
                AuditEventType.ACTION_EXECUTION_STARTED,
                AuditEventType.ACTION_EXECUTION_COMPLETED);
    }

    @Test
    void aiPlannerWriteActionStopsAtApprovalBoundary() {
        ActionDefinition definition = definition("UpdateReport", ActionEffect.WRITE, Set.of(ActionOrigin.AI_PLANNER));
        FlowActionRuntime runtime = new FlowActionRuntime(new InMemoryActionRegistry(List.of(new StubExecutor(
                definition,
                ActionExecutionResult.succeeded(Map.of())))));

        ActionExecutionResult result = runtime.handle(
                new ActionProposal(
                        "proposal-1",
                        "UpdateReport",
                        ActionOrigin.AI_PLANNER,
                        "planner",
                        "update report",
                        0.9d,
                        Map.of(),
                        null,
                        Map.of()),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(result.output()).containsKey("approvalId");
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
