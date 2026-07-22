package io.github.flowerjvm.flower.action.runtime.workflow;

import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.SynchronousActionExecutor;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEvent;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEventType;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
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
        ActionDefinition definition = definition(
                "CreateReport", ActionEffect.WRITE, Set.of(ActionProposerType.USER));
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
                userProposal("CreateReport", Map.of("siteId", 1)),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(audit.types()).containsSequence(
                AuditEventType.ACTION_PROPOSED,
                AuditEventType.ACTION_RESOLVED,
                AuditEventType.VALIDATION_COMPLETED,
                AuditEventType.POLICY_EVALUATED,
                AuditEventType.PRE_EXECUTION_CHECKED,
                AuditEventType.ACTION_EXECUTION_STARTED,
                AuditEventType.ACTION_EXECUTION_COMPLETED);
    }

    @Test
    void aiPlannerWriteActionStopsAtApprovalBoundary() {
        ActionDefinition definition = definition(
                "UpdateReport", ActionEffect.WRITE, Set.of(ActionProposerType.AI_PLANNER));
        WorkflowActionRuntime runtime = new WorkflowActionRuntime(new InMemoryActionRegistry(List.of(new StubExecutor(
                definition,
                ActionExecutionResult.succeeded(Map.of())))));

        ActionExecutionResult result = runtime.handle(
                new ActionProposal(
                        "proposal-1",
                        "UpdateReport",
                        ActionRequestChannel.COMMAND,
                        ActionProposerType.AI_PLANNER,
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

    @Test
    void deferredActionCanCompleteAfterObservableFlowFinishes() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        ActionDefinition definition = definition(
                "Maintenance", ActionEffect.WRITE, Set.of(ActionProposerType.USER));
        DeferredActionExecutor executor = new DeferredActionExecutor() {
            @Override
            public ActionDefinition definition() {
                return definition;
            }

            @Override
            public ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context) {
                return ActionDispatch.awaiting("operation-workflow", null, Map.of());
            }
        };
        WorkflowActionRuntime runtime = new WorkflowActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                PolicyGate.allowAll(),
                null,
                null,
                null,
                null,
                null,
                null,
                32,
                runStore);
        ExecutionContext context = new ExecutionContext(
                "tenant-1", "user-1", "run-workflow-deferred", "trace-workflow-deferred", Map.of());

        ActionExecutionResult accepted = runtime.handle(
                userProposal("Maintenance", Map.of()),
                context);
        var waiting = runStore.find(context.runId()).orElseThrow();
        ActionExecutionResult completed = runtime.complete(
                context.runId(),
                waiting.attemptToken(),
                ActionExecutionResult.succeeded(Map.of("done", true)));

        assertThat(accepted.status()).isEqualTo(ActionExecutionStatus.ACCEPTED);
        assertThat(completed.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(runStore.find(context.runId()).orElseThrow().status()).isEqualTo(ActionRunStatus.SUCCEEDED);
    }

    private static ActionDefinition definition(
            String actionId,
            ActionEffect effect,
            Set<ActionProposerType> allowedProposerTypes) {
        return new ActionDefinition(
                actionId,
                actionId,
                "",
                effect,
                ActionRiskLevel.MEDIUM,
                Set.of(ActionRequestChannel.COMMAND),
                allowedProposerTypes,
                Set.of(),
                false,
                false,
                true,
                "",
                "",
                Map.of());
    }

    private static ActionProposal userProposal(String actionId, Map<String, Object> input) {
        return ActionProposal.userFrom(ActionRequestChannel.COMMAND, actionId, input, "user-1");
    }

    private record StubExecutor(ActionDefinition definition, ActionExecutionResult result)
            implements SynchronousActionExecutor {
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
