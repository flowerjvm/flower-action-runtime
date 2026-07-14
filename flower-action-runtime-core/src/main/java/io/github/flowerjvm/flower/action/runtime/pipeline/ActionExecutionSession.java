package io.github.flowerjvm.flower.action.runtime.pipeline;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.ActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEvent;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEventType;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecision;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * Mutable state shared across the stages of one controlled action execution.
 *
 * <p>It carries the immutable request ({@link ActionProposal}, {@link ExecutionContext}), the runtime
 * collaborators, and the intermediate results each stage produces. Both the direct runtime and the Flower Flow
 * backend build one session per {@code handle} call and thread it through {@link ActionPipeline} stages.</p>
 */
public final class ActionExecutionSession {
    private final ActionProposal proposal;
    private final ExecutionContext context;
    private final ActionRegistry registry;
    private final ActionInputValidator inputValidator;
    private final PolicyGate policyGate;
    private final ApprovalGate approvalGate;
    private final DuplicateActionPolicy duplicateActionPolicy;
    private final AuditSink auditSink;
    private final TraceSink traceSink;
    private final RunStore runStore;

    private ActionRun run;
    private DuplicateActionDecision duplicateDecision;
    private ActionExecutor executor;
    private ActionDefinition definition;
    private ValidationResult validationResult;
    private PolicyDecision policyDecision;
    private ActionExecutionResult result;
    private boolean actionExecutionStarted;

    public ActionExecutionSession(
            ActionProposal proposal,
            ExecutionContext context,
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink,
            RunStore runStore) {
        this.proposal = Objects.requireNonNull(proposal, "proposal must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.inputValidator = Objects.requireNonNull(inputValidator, "inputValidator must not be null");
        this.policyGate = Objects.requireNonNull(policyGate, "policyGate must not be null");
        this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate must not be null");
        this.duplicateActionPolicy =
                Objects.requireNonNull(duplicateActionPolicy, "duplicateActionPolicy must not be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink must not be null");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink must not be null");
        this.runStore = Objects.requireNonNull(runStore, "runStore must not be null");
        this.run = ActionRun.requested(proposal, context);
    }

    public static ActionExecutionSession forResume(
            ActionRun existingRun,
            ActionProposal proposal,
            ExecutionContext context,
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink,
            RunStore runStore) {
        ActionExecutionSession session = new ActionExecutionSession(
                proposal,
                context,
                registry,
                inputValidator,
                policyGate,
                approvalGate,
                duplicateActionPolicy,
                auditSink,
                traceSink,
                runStore);
        session.run = Objects.requireNonNull(existingRun, "existingRun must not be null");
        session.duplicateDecision(DuplicateActionDecision.accept());
        return session;
    }

    public ActionProposal proposal() {
        return proposal;
    }

    public ExecutionContext context() {
        return context;
    }

    public ActionRun run() {
        return run;
    }

    void beginRun(String stageId) {
        run = run.toBuilder()
                .currentStage(stageId)
                .updatedAt(Instant.now())
                .build();
        runStore.create(run);
    }

    void enterStage(String stageId) {
        updateRun(current -> current.toBuilder().currentStage(stageId).build());
    }

    void updateRun(UnaryOperator<ActionRun> op) {
        run = Objects.requireNonNull(op.apply(run), "updated run must not be null")
                .toBuilder()
                .updatedAt(Instant.now())
                .build();
        runStore.update(run);
    }

    ActionRegistry registry() {
        return registry;
    }

    ActionInputValidator inputValidator() {
        return inputValidator;
    }

    PolicyGate policyGate() {
        return policyGate;
    }

    ApprovalGate approvalGate() {
        return approvalGate;
    }

    DuplicateActionPolicy duplicateActionPolicy() {
        return duplicateActionPolicy;
    }

    DuplicateActionDecision duplicateDecision() {
        return duplicateDecision;
    }

    void duplicateDecision(DuplicateActionDecision duplicateDecision) {
        this.duplicateDecision = duplicateDecision;
    }

    ActionExecutor executor() {
        return executor;
    }

    void executor(ActionExecutor executor) {
        this.executor = executor;
    }

    ActionDefinition definition() {
        return definition;
    }

    void definition(ActionDefinition definition) {
        this.definition = definition;
    }

    ValidationResult validationResult() {
        return validationResult;
    }

    void validationResult(ValidationResult validationResult) {
        this.validationResult = validationResult;
    }

    PolicyDecision policyDecision() {
        return policyDecision;
    }

    void policyDecision(PolicyDecision policyDecision) {
        this.policyDecision = policyDecision;
    }

    public ActionExecutionResult result() {
        return result == null ? ActionExecutionResult.failed("Action runtime produced no result.") : result;
    }

    public void result(ActionExecutionResult result) {
        this.result = result;
    }

    public boolean hasResult() {
        return result != null;
    }

    boolean actionExecutionStarted() {
        return actionExecutionStarted;
    }

    void markActionExecutionStarted() {
        this.actionExecutionStarted = true;
    }

    void record(AuditEventType type, Map<String, Object> payload) {
        AuditEvent event = AuditEvent.of(type, proposal, context, payload);
        auditSink.record(event);
        traceSink.record(event);
    }
}
