package io.github.flowerjvm.flower.action.runtime.workflow;

import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.ActionOrigin;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEvent;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEventType;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowActionRuntimeTest {
    @Test
    void executesRegisteredActionThroughFlowerFlow() {
        RecordingAuditSink audit = new RecordingAuditSink();
        ActionDefinition definition = definition("CreateReport", ActionEffect.WRITE, Set.of(ActionOrigin.USER));
        WorkflowActionRuntime runtime = new WorkflowActionRuntime(
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
        WorkflowActionRuntime runtime = new WorkflowActionRuntime(new InMemoryActionRegistry(List.of(new StubExecutor(
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
