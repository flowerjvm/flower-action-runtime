package io.github.parkkevinsb.flower.agent.runtime;

import java.util.Map;
import java.util.Objects;

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
            TraceSink traceSink) {
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
    }

    public ActionProposal proposal() {
        return proposal;
    }

    public ExecutionContext context() {
        return context;
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
