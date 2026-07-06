package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.ActionEffect;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionStatus;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutor;
import io.github.parkkevinsb.flower.agent.runtime.ActionInputValidator;
import io.github.parkkevinsb.flower.agent.runtime.ActionOrigin;
import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.ActionRiskLevel;
import io.github.parkkevinsb.flower.agent.runtime.ActionRuntime;
import io.github.parkkevinsb.flower.agent.runtime.AuditEvent;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.AuditSink;
import io.github.parkkevinsb.flower.agent.runtime.DefaultActionRuntime;
import io.github.parkkevinsb.flower.agent.runtime.DuplicateActionDecision;
import io.github.parkkevinsb.flower.agent.runtime.DuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.InMemoryActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.InMemoryDuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.PolicyDecision;
import io.github.parkkevinsb.flower.agent.runtime.PolicyDecisionType;
import io.github.parkkevinsb.flower.agent.runtime.PolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the direct runtime and the Flower Flow backend are behaviorally identical.
 *
 * <p>Both are driven with the same proposals and collaborators; the resulting outcome and the full audit trail
 * (event types + payloads, minus volatile ids) must match. This guards the single-source-of-truth invariant:
 * neither backend may drift in governance behavior or audit output.</p>
 */
class RuntimeParityTest {

    @Test
    void successPathParity() {
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null,
                PolicyGate.allowAll(),
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of("siteId", 1), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void unknownActionParity() {
        assertParity(
                () -> new InMemoryActionRegistry(List.of()),
                null,
                null,
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("UnknownAction", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void validationFailureParity() {
        ActionInputValidator invalid = (proposal, definition, context) -> ValidationResult.invalid("missing siteId");
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                PolicyGate.allowAll(),
                invalid,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void aiPlannerApprovalParity() {
        assertParity(
                () -> registryOf(writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                null,
                null,
                runtime -> runtime.handle(
                        new ActionProposal("proposal-1", "UpdateReport", ActionOrigin.AI_PLANNER,
                                "planner", "update", 0.9d, Map.of(), null, Map.of()),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void policyDenyParity() {
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                PolicyGate.denyAll("blocked by policy"),
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void executorExceptionParity() {
        assertParity(
                () -> new InMemoryActionRegistry(List.of(
                        new ThrowingExecutor(writeAction("CreateReport", Set.of(ActionOrigin.USER)), "boom"))),
                null,
                PolicyGate.allowAll(),
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void validatorExceptionParityAndDuplicateCleanup() {
        ActionInputValidator throwingValidator = (proposal, definition, context) -> {
            throw new RuntimeException("validator boom");
        };
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");
        ActionProposal first = new ActionProposal("proposal-1", "CreateReport", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        ActionProposal second = new ActionProposal("proposal-2", "CreateReport", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                InMemoryDuplicateActionPolicy::new,
                PolicyGate.allowAll(),
                throwingValidator,
                runtime -> {
                    ActionExecutionResult firstResult = runtime.handle(first, context);
                    ActionExecutionResult secondResult = runtime.handle(second, context);
                    assertThat(firstResult.status()).isEqualTo(secondResult.status());
                    assertThat(firstResult.message()).isEqualTo(secondResult.message());
                    return secondResult;
                });
    }

    @Test
    void transientValidatorExceptionReleasesDuplicateWithoutCachingFailure() {
        ActionExecutionResult directResult = runTransientValidatorSequence(false);
        ActionExecutionResult flowResult = runTransientValidatorSequence(true);

        assertThat(flowResult.status()).isEqualTo(directResult.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(flowResult.output()).isEqualTo(directResult.output()).containsEntry("reportId", 99);
    }

    @Test
    void policyExceptionParity() {
        PolicyGate throwingPolicy = (proposal, definition, context) -> {
            throw new RuntimeException("policy boom");
        };
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                throwingPolicy,
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void duplicatePolicyExceptionParity() {
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                ThrowingDuplicateActionPolicy::new,
                PolicyGate.allowAll(),
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void auditExceptionParity() {
        ActionProposal proposal = ActionProposal.user("CreateReport", Map.of("siteId", 1), "user-1");
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");

        ThrowingAuditSink directAudit = new ThrowingAuditSink();
        ActionRuntime direct = new DefaultActionRuntime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null, null, null, null, directAudit, null);
        ActionExecutionResult directResult = direct.handle(proposal, context);

        ThrowingAuditSink flowAudit = new ThrowingAuditSink();
        ActionRuntime flow = new FlowActionRuntime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null, null, null, null, flowAudit, null, null, null, 64);
        ActionExecutionResult flowResult = flow.handle(proposal, context);

        assertThat(flowResult.status()).isEqualTo(directResult.status());
        assertThat(flowResult.message()).isEqualTo(directResult.message());
        assertThat(flowAudit.calls()).isEqualTo(directAudit.calls());
    }

    @Test
    void completionAuditFailurePreservesSuccessfulResultAndDoesNotReleaseDuplicate() {
        TrackingDuplicateActionPolicy directDuplicate = new TrackingDuplicateActionPolicy();
        CompletionAuditThrowsSink directAudit = new CompletionAuditThrowsSink();
        ActionRuntime direct = new DefaultActionRuntime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null, PolicyGate.allowAll(), null, directDuplicate, directAudit, null);

        TrackingDuplicateActionPolicy flowDuplicate = new TrackingDuplicateActionPolicy();
        CompletionAuditThrowsSink flowAudit = new CompletionAuditThrowsSink();
        ActionRuntime flow = new FlowActionRuntime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null, PolicyGate.allowAll(), null, flowDuplicate, flowAudit, null, null, null, 64);

        ActionProposal proposal = new ActionProposal("proposal-1", "CreateReport", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");

        ActionExecutionResult directResult = direct.handle(proposal, context);
        ActionExecutionResult flowResult = flow.handle(proposal, context);

        assertThat(flowResult.status()).isEqualTo(directResult.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(flowResult.output()).isEqualTo(directResult.output()).containsEntry("reportId", 10);
        assertThat(directDuplicate.completeCalls()).isZero();
        assertThat(flowDuplicate.completeCalls()).isZero();
        assertThat(directDuplicate.releaseCalls()).isZero();
        assertThat(flowDuplicate.releaseCalls()).isZero();
        assertThat(project(flowAudit.events())).isEqualTo(project(directAudit.events()));
    }

    @Test
    void completeFailurePreservesSuccessfulResultParity() {
        CompleteThrowsDuplicateActionPolicy directDuplicate = new CompleteThrowsDuplicateActionPolicy();
        RecordingAuditSink directAudit = new RecordingAuditSink();
        ActionRuntime direct = new DefaultActionRuntime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null, PolicyGate.allowAll(), null, directDuplicate, directAudit, null);

        CompleteThrowsDuplicateActionPolicy flowDuplicate = new CompleteThrowsDuplicateActionPolicy();
        RecordingAuditSink flowAudit = new RecordingAuditSink();
        ActionRuntime flow = new FlowActionRuntime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null, PolicyGate.allowAll(), null, flowDuplicate, flowAudit, null, null, null, 64);

        ActionProposal proposal = new ActionProposal("proposal-1", "CreateReport", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");

        ActionExecutionResult directResult = direct.handle(proposal, context);
        ActionExecutionResult flowResult = flow.handle(proposal, context);

        assertThat(flowResult.status()).isEqualTo(directResult.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(flowResult.output()).isEqualTo(directResult.output()).containsEntry("reportId", 10);
        assertThat(flowResult.message()).isEqualTo(directResult.message());
        assertThat(project(flowAudit.events())).isEqualTo(project(directAudit.events()));
        assertThat(directDuplicate.completeCalls()).isEqualTo(1);
        assertThat(flowDuplicate.completeCalls()).isEqualTo(1);
        assertThat(directDuplicate.releaseCalls()).isZero();
        assertThat(flowDuplicate.releaseCalls()).isZero();
    }

    @Test
    void pendingApprovalReleasesDuplicateReservationInsteadOfCachingResult() {
        TrackingDuplicateActionPolicy directDuplicate = new TrackingDuplicateActionPolicy();
        ActionRuntime direct = new DefaultActionRuntime(
                registryOf(writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null, null, null, directDuplicate, null, null);

        TrackingDuplicateActionPolicy flowDuplicate = new TrackingDuplicateActionPolicy();
        ActionRuntime flow = new FlowActionRuntime(
                registryOf(writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null, null, null, flowDuplicate, null, null, null, null, 64);

        ActionProposal proposal = new ActionProposal("proposal-1", "UpdateReport", ActionOrigin.AI_PLANNER,
                "planner", "update", 0.9d, Map.of(), "same-key", Map.of());
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");

        assertThat(direct.handle(proposal, context).status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(flow.handle(proposal, context).status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(directDuplicate.completeCalls()).isZero();
        assertThat(flowDuplicate.completeCalls()).isZero();
        assertThat(directDuplicate.releaseCalls()).isEqualTo(1);
        assertThat(flowDuplicate.releaseCalls()).isEqualTo(1);
    }

    @Test
    void dryRunParity() {
        PolicyGate dryRun = (proposal, definition, context) ->
                new PolicyDecision(PolicyDecisionType.REQUIRE_DRY_RUN, "dry run first", Map.of());
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 7))),
                null,
                dryRun,
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void duplicateReturnExistingParity() {
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");
        ActionProposal first = new ActionProposal("proposal-1", "ReadStatus", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        ActionProposal second = new ActionProposal("proposal-2", "ReadStatus", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        assertParity(
                () -> new InMemoryActionRegistry(List.of(new StubExecutor(
                        definition("ReadStatus", ActionEffect.READ_ONLY, Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("status", "ok"))))),
                InMemoryDuplicateActionPolicy::new,
                null,
                null,
                runtime -> {
                    runtime.handle(first, context);
                    return runtime.handle(second, context);
                });
    }

    // --- parity harness -------------------------------------------------------------------------

    private void assertParity(
            Supplier<ActionRegistry> registry,
            Supplier<DuplicateActionPolicy> duplicatePolicy,
            PolicyGate policyGate,
            ActionInputValidator validator,
            Function<ActionRuntime, ActionExecutionResult> body) {
        RecordingAuditSink directAudit = new RecordingAuditSink();
        ActionRuntime direct = new DefaultActionRuntime(
                registry.get(), validator, policyGate, null, supply(duplicatePolicy), directAudit, null);
        ActionExecutionResult directResult = body.apply(direct);

        RecordingAuditSink flowAudit = new RecordingAuditSink();
        ActionRuntime flow = new FlowActionRuntime(
                registry.get(), validator, policyGate, null, supply(duplicatePolicy), flowAudit, null, null, null, 64);
        ActionExecutionResult flowResult = body.apply(flow);

        assertThat(flowResult.status()).isEqualTo(directResult.status());
        assertThat(flowResult.message()).isEqualTo(directResult.message());
        assertThat(stripVolatile(flowResult.output())).isEqualTo(stripVolatile(directResult.output()));
        assertThat(project(flowAudit.events())).isEqualTo(project(directAudit.events()));
    }

    private static DuplicateActionPolicy supply(Supplier<DuplicateActionPolicy> supplier) {
        return supplier == null ? null : supplier.get();
    }

    private static ActionExecutionResult runTransientValidatorSequence(boolean flowRuntime) {
        FailsOnceValidator validator = new FailsOnceValidator();
        ActionRegistry registry = registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                ActionExecutionResult.succeeded(Map.of("reportId", 99)));
        DuplicateActionPolicy duplicatePolicy = new InMemoryDuplicateActionPolicy();
        ActionRuntime runtime = flowRuntime
                ? new FlowActionRuntime(registry, validator, PolicyGate.allowAll(), null,
                        duplicatePolicy, null, null, null, null, 64)
                : new DefaultActionRuntime(registry, validator, PolicyGate.allowAll(), null,
                        duplicatePolicy, null, null);
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");
        ActionProposal first = new ActionProposal("proposal-1", "CreateReport", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        ActionProposal second = new ActionProposal("proposal-2", "CreateReport", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());

        ActionExecutionResult firstResult = runtime.handle(first, context);
        assertThat(firstResult.status()).isEqualTo(ActionExecutionStatus.FAILED);
        return runtime.handle(second, context);
    }

    private static List<Map<String, Object>> project(List<AuditEvent> events) {
        List<Map<String, Object>> projected = new ArrayList<>();
        for (AuditEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", event.type());
            row.put("payload", stripVolatile(event.payload()));
            projected.add(row);
        }
        return projected;
    }

    private static Map<String, Object> stripVolatile(Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.remove("approvalId");
        return copy;
    }

    private static ActionRegistry registryOf(ActionDefinition definition, ActionExecutionResult result) {
        return new InMemoryActionRegistry(List.of(new StubExecutor(definition, result)));
    }

    private static ActionDefinition writeAction(String actionId, Set<ActionOrigin> allowedOrigins) {
        return definition(actionId, ActionEffect.WRITE, allowedOrigins);
    }

    private static ActionDefinition definition(
            String actionId, ActionEffect effect, Set<ActionOrigin> allowedOrigins) {
        return new ActionDefinition(actionId, actionId, "", effect, ActionRiskLevel.MEDIUM,
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

    private static final class FailsOnceValidator implements ActionInputValidator {
        private int calls;

        @Override
        public ValidationResult validate(
                ActionProposal proposal,
                ActionDefinition definition,
                ExecutionContext context) {
            calls++;
            if (calls == 1) {
                throw new RuntimeException("validator boom");
            }
            return ValidationResult.ok();
        }
    }

    private static final class ThrowingDuplicateActionPolicy implements DuplicateActionPolicy {
        @Override
        public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
            throw new RuntimeException("duplicate boom");
        }

        @Override
        public void complete(ActionProposal proposal, ActionExecutionResult result) {
            throw new RuntimeException("duplicate complete boom");
        }

        @Override
        public void release(ActionProposal proposal, Throwable cause) {
            throw new RuntimeException("duplicate release boom");
        }
    }

    private static class TrackingDuplicateActionPolicy implements DuplicateActionPolicy {
        private int completeCalls;
        private int releaseCalls;

        @Override
        public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
            return DuplicateActionDecision.accept();
        }

        @Override
        public void complete(ActionProposal proposal, ActionExecutionResult result) {
            completeCalls++;
        }

        @Override
        public void release(ActionProposal proposal, Throwable cause) {
            releaseCalls++;
        }

        int completeCalls() {
            return completeCalls;
        }

        int releaseCalls() {
            return releaseCalls;
        }
    }

    private static final class CompleteThrowsDuplicateActionPolicy extends TrackingDuplicateActionPolicy {
        @Override
        public void complete(ActionProposal proposal, ActionExecutionResult result) {
            super.complete(proposal, result);
            throw new RuntimeException("complete boom");
        }
    }

    private static final class RecordingAuditSink implements AuditSink {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        List<AuditEvent> events() {
            return events;
        }
    }

    private static final class ThrowingAuditSink implements AuditSink {
        private int calls;

        @Override
        public void record(AuditEvent event) {
            calls++;
            throw new RuntimeException("audit boom");
        }

        int calls() {
            return calls;
        }
    }

    private static final class CompletionAuditThrowsSink implements AuditSink {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            if (event.type() == AuditEventType.ACTION_EXECUTION_COMPLETED) {
                throw new RuntimeException("completion audit boom");
            }
            events.add(event);
        }

        List<AuditEvent> events() {
            return events;
        }
    }
}
