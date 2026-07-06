package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutor;
import io.github.parkkevinsb.flower.agent.runtime.ActionInputValidator;
import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.ApprovalGate;
import io.github.parkkevinsb.flower.agent.runtime.AuditEvent;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.AuditSink;
import io.github.parkkevinsb.flower.agent.runtime.DuplicateActionDecision;
import io.github.parkkevinsb.flower.agent.runtime.DuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.PolicyDecision;
import io.github.parkkevinsb.flower.agent.runtime.PolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.TraceSink;
import io.github.parkkevinsb.flower.agent.runtime.ValidationResult;

import java.util.Map;

public final class FlowActionExecutionSession {
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

    FlowActionExecutionSession(
            ActionProposal proposal,
            ExecutionContext context,
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink) {
        this.proposal = proposal;
        this.context = context;
        this.registry = registry;
        this.inputValidator = inputValidator;
        this.policyGate = policyGate;
        this.approvalGate = approvalGate;
        this.duplicateActionPolicy = duplicateActionPolicy;
        this.auditSink = auditSink;
        this.traceSink = traceSink;
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
        return result == null ? ActionExecutionResult.failed("Flow action runtime produced no result.") : result;
    }

    void result(ActionExecutionResult result) {
        this.result = result;
    }

    boolean hasResult() {
        return result != null;
    }

    void record(AuditEventType type, Map<String, Object> payload) {
        AuditEvent event = AuditEvent.of(type, proposal, context, payload);
        auditSink.record(event);
        traceSink.record(event);
    }
}
