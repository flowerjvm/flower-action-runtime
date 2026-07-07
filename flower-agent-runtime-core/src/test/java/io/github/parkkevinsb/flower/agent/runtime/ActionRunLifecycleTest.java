package io.github.parkkevinsb.flower.agent.runtime;

import io.github.parkkevinsb.flower.agent.runtime.action.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionEffect;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionExecutor;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionRiskLevel;
import io.github.parkkevinsb.flower.agent.runtime.action.InMemoryActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.approval.ApprovalDecision;
import io.github.parkkevinsb.flower.agent.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.policy.PolicyDecisionType;
import io.github.parkkevinsb.flower.agent.runtime.policy.PolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.run.ActionRun;
import io.github.parkkevinsb.flower.agent.runtime.run.ActionRunStatus;
import io.github.parkkevinsb.flower.agent.runtime.run.InMemoryRunStore;
import io.github.parkkevinsb.flower.agent.runtime.run.RunStore;
import io.github.parkkevinsb.flower.agent.runtime.validation.ActionInputValidator;
import io.github.parkkevinsb.flower.agent.runtime.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRunLifecycleTest {
    @Test
    void storesSuccessfulRunLifecycle() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        DefaultActionRuntime runtime = runtime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null,
                PolicyGate.allowAll(),
                runStore);
        ExecutionContext context = context("run-success");

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of("siteId", 1), "user-1"),
                context);

        ActionRun run = stored(runStore, context);
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(run.runId()).isEqualTo(context.runId());
        assertThat(run.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(run.currentStage()).isEqualTo("execute-action");
        assertThat(run.result()).isEqualTo(result);
        assertThat(run.attemptToken()).isNotBlank();
        assertThat(run.input()).containsEntry("siteId", 1);
    }

    @Test
    void storesUnknownActionAsDeniedRun() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        DefaultActionRuntime runtime = runtime(new InMemoryActionRegistry(List.of()), null, null, runStore);
        ExecutionContext context = context("run-unknown");

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("MissingAction", Map.of(), "user-1"),
                context);

        ActionRun run = stored(runStore, context);
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(run.status()).isEqualTo(ActionRunStatus.DENIED);
        assertThat(run.failureReason()).contains("not registered");
        assertThat(run.result()).isEqualTo(result);
    }

    @Test
    void storesValidationFailureAsDeniedRun() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        ActionInputValidator invalid = (proposal, definition, context) -> ValidationResult.invalid("missing siteId");
        DefaultActionRuntime runtime = runtime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                invalid,
                PolicyGate.allowAll(),
                runStore);
        ExecutionContext context = context("run-invalid");

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of(), "user-1"),
                context);

        ActionRun run = stored(runStore, context);
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.VALIDATION_FAILED);
        assertThat(run.status()).isEqualTo(ActionRunStatus.DENIED);
        assertThat(run.failureReason()).contains("missing siteId");
        assertThat(run.result()).isEqualTo(result);
    }

    @Test
    void storesPolicyDenyAsDeniedRun() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        DefaultActionRuntime runtime = runtime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                PolicyGate.denyAll("blocked"),
                runStore);
        ExecutionContext context = context("run-policy-deny");

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of(), "user-1"),
                context);

        ActionRun run = stored(runStore, context);
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(run.status()).isEqualTo(ActionRunStatus.DENIED);
        assertThat(run.policyDecisionType()).isEqualTo(PolicyDecisionType.DENY);
        assertThat(run.policyReason()).isEqualTo("blocked");
        assertThat(run.failureReason()).isEqualTo("blocked");
    }

    @Test
    void storesAiPlannerWriteAsWaitingApprovalRun() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        DefaultActionRuntime runtime = runtime(
                registryOf(writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                null,
                runStore);
        ExecutionContext context = context("run-approval");

        ActionExecutionResult result = runtime.handle(
                new ActionProposal("proposal-approval", "UpdateReport", ActionOrigin.AI_PLANNER,
                        "planner", "update", 0.9d, Map.of(), null, Map.of()),
                context);

        ActionRun run = stored(runStore, context);
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(run.status()).isEqualTo(ActionRunStatus.WAITING_APPROVAL);
        assertThat(run.approvalId()).isNotBlank();
        assertThat(run.result()).isEqualTo(result);
        assertThat(run.policyDecisionType()).isEqualTo(PolicyDecisionType.REQUIRE_APPROVAL);
    }

    @Test
    void capturesProposalRationaleAndRestoresItOnResume() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        ProposalCapturingExecutor executor = new ProposalCapturingExecutor(
                writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                ActionExecutionResult.succeeded(Map.of("updated", true)));
        DefaultActionRuntime runtime = runtime(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                null,
                runStore);
        ExecutionContext context = context("run-proposal-rationale");
        ActionProposal proposal = new ActionProposal(
                "proposal-rationale",
                "UpdateReport",
                ActionOrigin.AI_PLANNER,
                "planner",
                "source-backed document issue",
                0.72d,
                Map.of("siteId", 1),
                null,
                Map.of("model", "x"));

        ActionExecutionResult parked = runtime.handle(proposal, context);
        ActionRun waiting = stored(runStore, context);

        assertThat(parked.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(waiting.proposalReason()).isEqualTo(proposal.reason());
        assertThat(waiting.proposalConfidence()).isEqualTo(proposal.confidence());
        assertThat(waiting.proposalMetadata()).isEqualTo(proposal.metadata());

        ActionExecutionResult resumed = runtime.resume(
                context.runId(),
                ApprovalDecision.approved(waiting.approvalId(), "admin"));

        ActionRun completed = stored(runStore, context);
        assertThat(resumed.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(completed.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(executor.proposal().reason()).isEqualTo(proposal.reason());
        assertThat(executor.proposal().confidence()).isEqualTo(proposal.confidence());
        assertThat(executor.proposal().metadata()).isEqualTo(proposal.metadata());
    }

    @Test
    void storesExecutorExceptionAsFailedRun() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        DefaultActionRuntime runtime = runtime(
                new InMemoryActionRegistry(List.of(
                        new ThrowingExecutor(writeAction("CreateReport", Set.of(ActionOrigin.USER)), "boom"))),
                null,
                PolicyGate.allowAll(),
                runStore);
        ExecutionContext context = context("run-executor-failed");

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of(), "user-1"),
                context);

        ActionRun run = stored(runStore, context);
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(run.status()).isEqualTo(ActionRunStatus.FAILED);
        assertThat(run.failureReason()).contains("boom");
        assertThat(run.attemptToken()).isNotBlank();
        assertThat(run.result()).isEqualTo(result);
    }

    @Test
    void storesGateExceptionAsRuntimeFailedRun() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        ActionInputValidator throwingValidator = (proposal, definition, context) -> {
            throw new RuntimeException("validator boom");
        };
        DefaultActionRuntime runtime = runtime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                throwingValidator,
                PolicyGate.allowAll(),
                runStore);
        ExecutionContext context = context("run-runtime-failed");

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("CreateReport", Map.of(), "user-1"),
                context);

        ActionRun run = stored(runStore, context);
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(run.status()).isEqualTo(ActionRunStatus.RUNTIME_FAILED);
        assertThat(run.failureReason()).contains("validator boom");
        assertThat(run.result()).isEqualTo(result);
    }

    private static DefaultActionRuntime runtime(
            ActionRegistry registry,
            ActionInputValidator validator,
            PolicyGate policyGate,
            InMemoryRunStore runStore) {
        return new DefaultActionRuntime(
                registry,
                validator,
                policyGate,
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null,
                runStore);
    }

    private static ExecutionContext context(String runId) {
        return new ExecutionContext("tenant-1", "user-1", runId, runId + "-trace", Map.of());
    }

    private static ActionRun stored(InMemoryRunStore runStore, ExecutionContext context) {
        return runStore.find(context.runId()).orElseThrow();
    }

    private static ActionRegistry registryOf(ActionDefinition definition, ActionExecutionResult result) {
        return new InMemoryActionRegistry(List.of(new StubExecutor(definition, result)));
    }

    private static ActionDefinition writeAction(String actionId, Set<ActionOrigin> allowedOrigins) {
        return new ActionDefinition(actionId, actionId, "", ActionEffect.WRITE, ActionRiskLevel.MEDIUM,
                allowedOrigins, Set.of(), false, false, true, "", "", Map.of());
    }

    private record StubExecutor(ActionDefinition definition, ActionExecutionResult result) implements ActionExecutor {
        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            return result;
        }
    }

    private record ThrowingExecutor(ActionDefinition definition, String message) implements ActionExecutor {
        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            throw new RuntimeException(message);
        }
    }

    private static final class ProposalCapturingExecutor implements ActionExecutor {
        private final ActionDefinition definition;
        private final ActionExecutionResult result;
        private ActionProposal proposal;

        private ProposalCapturingExecutor(ActionDefinition definition, ActionExecutionResult result) {
            this.definition = definition;
            this.result = result;
        }

        @Override
        public ActionDefinition definition() {
            return definition;
        }

        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            proposal = context.proposal();
            return result;
        }

        private ActionProposal proposal() {
            return proposal;
        }
    }
}
