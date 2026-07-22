package io.github.flowerjvm.flower.action.runtime;

import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.action.SynchronousActionExecutor;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEvent;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEventType;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecisionType;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
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
        ActionDefinition definition = definition("CreateReport", ActionEffect.WRITE, Set.of(ActionProposerType.USER));
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
                userProposal("CreateReport", Map.of("siteId", 1), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(result.output()).containsEntry("reportId", 10);
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
    void keepsRequestChannelProposerAndExecutionPrincipalSeparate() {
        ActionProposal proposal = ActionProposal.userFrom(
                ActionRequestChannel.API,
                "ReadStatus",
                Map.of(),
                "proposer-user");
        ExecutionContext context = new ExecutionContext(
                "tenant-1",
                "principal-user",
                "run-identity",
                "trace-identity",
                Map.of());

        assertThat(proposal.requestChannel()).isEqualTo(ActionRequestChannel.API);
        assertThat(proposal.proposerType()).isEqualTo(ActionProposerType.USER);
        assertThat(proposal.requesterId()).isEqualTo("proposer-user");
        assertThat(context.userId()).isEqualTo("principal-user");
    }

    @Test
    void duplicateRunIdFailureDoesNotOverwriteExistingRun() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        ExecutionContext context = new ExecutionContext(
                "tenant-1", "user-1", "run-already-exists", "trace-1", Map.of());
        ActionProposal proposal = userProposal("ReadStatus", Map.of(), "user-1");
        ActionRun existing = ActionRun.requested(proposal, context).toBuilder()
                .status(ActionRunStatus.SUCCEEDED)
                .result(ActionExecutionResult.succeeded(Map.of("original", true)))
                .build();
        runStore.create(existing);
        ActionDefinition definition = definition("ReadStatus", ActionEffect.READ_ONLY, Set.of(ActionProposerType.USER));
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(new StubExecutor(
                        definition,
                        ActionExecutionResult.succeeded(Map.of("replacement", true))))),
                null,
                PolicyGate.allowAll(),
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null,
                runStore);

        ActionExecutionResult result = runtime.handle(proposal, context);

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(runStore.find(context.runId())).contains(existing);
    }

    @Test
    void preExecutionGuardStopsSideEffectWithStableCode() {
        ActionDefinition definition = definition("CreateReport", ActionEffect.WRITE, Set.of(ActionProposerType.USER));
        AtomicInteger calls = new AtomicInteger();
        ActionExecutor executor = new SynchronousActionExecutor() {
            @Override
            public ActionDefinition definition() {
                return definition;
            }

            @Override
            public ActionExecutionResult execute(ActionExecutionContext context) {
                calls.incrementAndGet();
                return ActionExecutionResult.succeeded(Map.of());
            }
        };
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                ActionInputValidator.allowAll(),
                PolicyGate.allowAll(),
                ApprovalGate.unsupported(),
                new InMemoryDuplicateActionPolicy(),
                null,
                null,
                io.github.flowerjvm.flower.action.runtime.run.RunStore.noop(),
                PreExecutionGuard.denyAll("RESOURCE_VERSION_CHANGED", "Approved resource state is stale."));

        ActionExecutionResult result = runtime.handle(
                userProposal("CreateReport", Map.of(), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(result.code()).isEqualTo("RESOURCE_VERSION_CHANGED");
        assertThat(calls).hasValue(0);
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
                userProposal("UnknownAction", Map.of(), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(result.message()).contains("not registered");
        assertThat(audit.types()).contains(AuditEventType.ACTION_DENIED);
    }

    @Test
    void requiresApprovalForAiPlannerWriteActionByDefault() {
        ActionDefinition definition = definition(
                "UpdateReport", ActionEffect.WRITE, Set.of(ActionProposerType.AI_PLANNER));
        ActionExecutor executor = new StubExecutor(definition, ActionExecutionResult.succeeded(Map.of()));
        DefaultActionRuntime runtime = new DefaultActionRuntime(new InMemoryActionRegistry(List.of(executor)));

        ActionExecutionResult result = runtime.handle(
                new ActionProposal(
                        "proposal-1",
                        "UpdateReport",
                        ActionRequestChannel.COMMAND,
                        ActionProposerType.AI_PLANNER,
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
        ActionDefinition definition = definition(
                "ReadStatus", ActionEffect.READ_ONLY, Set.of(ActionProposerType.USER));
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
                ActionRequestChannel.COMMAND,
                ActionProposerType.USER,
                "user-1",
                "",
                1.0d,
                Map.of(),
                "same-key",
                Map.of());
        ActionProposal second = new ActionProposal(
                "proposal-2",
                "ReadStatus",
                ActionRequestChannel.COMMAND,
                ActionProposerType.USER,
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
    void policyDenialCannotReadAPreviouslyCompletedDuplicateResult() {
        ActionDefinition definition = definition(
                "ReadStatus", ActionEffect.READ_ONLY, Set.of(ActionProposerType.USER));
        AtomicInteger calls = new AtomicInteger();
        SynchronousActionExecutor executor = new SynchronousActionExecutor() {
            @Override
            public ActionDefinition definition() {
                return definition;
            }

            @Override
            public ActionExecutionResult execute(ActionExecutionContext context) {
                calls.incrementAndGet();
                return ActionExecutionResult.succeeded(Map.of("secret", "tenant-result"));
            }
        };
        PolicyGate principalPolicy = (proposal, action, context) -> "allowed-user".equals(context.userId())
                ? PolicyDecision.allow()
                : PolicyDecision.deny("principal is not authorized");
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                principalPolicy,
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null);
        ActionProposal first = userProposal("ReadStatus", Map.of(), "requester").toBuilder()
                .proposalId("proposal-authorized")
                .idempotencyKey("shared-result-key")
                .build();
        ActionProposal second = first.toBuilder()
                .proposalId("proposal-unauthorized")
                .build();

        ActionExecutionResult allowed = runtime.handle(
                first,
                ExecutionContext.of("tenant-1", "allowed-user"));
        ActionExecutionResult denied = runtime.handle(
                second,
                ExecutionContext.of("tenant-1", "denied-user"));

        assertThat(allowed.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(denied.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(denied.message()).contains("not authorized");
        assertThat(denied.output()).doesNotContainKey("secret");
        assertThat(calls).hasValue(1);
    }

    @Test
    void dryRunFailureStopsBeforeRealExecution() {
        ActionDefinition definition = dryRunnableDefinition(
                "CreateReport", ActionEffect.WRITE, Set.of(ActionProposerType.USER));
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
                userProposal("CreateReport", Map.of(), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(result.message()).contains("dry run rejected");
        assertThat(executor.dryRunCalls()).isEqualTo(1);
        assertThat(executor.executeCalls()).isZero();
    }

    @Test
    void dryRunRequiredButUnsupportedIsDeniedBeforeExecution() {
        ActionDefinition definition = definition(
                "CreateReport", ActionEffect.WRITE, Set.of(ActionProposerType.USER));
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
                userProposal("CreateReport", Map.of(), "user-1"),
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
                Set.of(ActionRequestChannel.COMMAND),
                Set.of(ActionProposerType.USER),
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
                userProposal("DeleteProject", Map.of(), "user-1"),
                ExecutionContext.of("tenant-1", "user-1"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
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

    private static ActionDefinition dryRunnableDefinition(
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
                true,
                false,
                true,
                "",
                "",
                Map.of());
    }

    private static ActionProposal userProposal(
            String actionId,
            Map<String, Object> input,
            String requesterId) {
        return ActionProposal.userFrom(ActionRequestChannel.COMMAND, actionId, input, requesterId);
    }

    private record StubExecutor(ActionDefinition definition, ActionExecutionResult result)
            implements SynchronousActionExecutor {
        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            return result;
        }
    }

    private static final class DryRunExecutor implements SynchronousActionExecutor {
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
