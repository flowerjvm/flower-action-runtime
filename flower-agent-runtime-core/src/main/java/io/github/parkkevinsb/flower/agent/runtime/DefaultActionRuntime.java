package io.github.parkkevinsb.flower.agent.runtime;

import java.util.Map;
import java.util.Objects;

public final class DefaultActionRuntime implements ActionRuntime {
    private final ActionRegistry registry;
    private final ActionInputValidator inputValidator;
    private final PolicyGate policyGate;
    private final ApprovalGate approvalGate;
    private final DuplicateActionPolicy duplicateActionPolicy;
    private final AuditSink auditSink;
    private final TraceSink traceSink;

    public DefaultActionRuntime(
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.inputValidator = inputValidator == null ? ActionInputValidator.allowAll() : inputValidator;
        this.policyGate = policyGate == null ? new DefaultPolicyGate() : policyGate;
        this.approvalGate = approvalGate == null ? ApprovalGate.unsupported() : approvalGate;
        this.duplicateActionPolicy = duplicateActionPolicy == null
                ? DuplicateActionPolicy.acceptAll()
                : duplicateActionPolicy;
        this.auditSink = auditSink == null ? AuditSink.noop() : auditSink;
        this.traceSink = traceSink == null ? TraceSink.noop() : traceSink;
    }

    public DefaultActionRuntime(ActionRegistry registry) {
        this(registry, null, null, null, null, null, null);
    }

    @Override
    public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(context, "context must not be null");
        record(AuditEventType.ACTION_PROPOSED, proposal, context, Map.of("origin", proposal.origin().name()));

        DuplicateActionDecision duplicate = duplicateActionPolicy.reserve(proposal, context);
        if (duplicate.type() == DuplicateActionDecisionType.RETURN_EXISTING) {
            ActionExecutionResult result = duplicate.existingResult();
            record(AuditEventType.ACTION_DUPLICATE, proposal, context, Map.of("decision", duplicate.type().name()));
            return result;
        }
        if (duplicate.type() == DuplicateActionDecisionType.REJECT) {
            ActionExecutionResult result = ActionExecutionResult.denied(duplicate.reason());
            record(AuditEventType.ACTION_DUPLICATE, proposal, context, Map.of("decision", duplicate.type().name()));
            return result;
        }

        ActionExecutionResult result = run(proposal, context);
        duplicateActionPolicy.complete(proposal, result);
        return result;
    }

    private ActionExecutionResult run(ActionProposal proposal, ExecutionContext context) {
        ActionExecutor executor = registry.findExecutor(proposal.actionId()).orElse(null);
        if (executor == null) {
            ActionExecutionResult result = ActionExecutionResult.denied("Action is not registered: " + proposal.actionId());
            record(AuditEventType.ACTION_DENIED, proposal, context, Map.of("reason", result.message()));
            return result;
        }
        ActionDefinition definition = executor.definition();
        record(AuditEventType.ACTION_RESOLVED, proposal, context, Map.of("riskLevel", definition.riskLevel().name()));

        ValidationResult validation = inputValidator.validate(proposal, definition, context);
        record(AuditEventType.VALIDATION_COMPLETED, proposal, context, Map.of("valid", validation.valid()));
        if (!validation.valid()) {
            ActionExecutionResult result = ActionExecutionResult.validationFailed(String.join("; ", validation.violations()));
            record(AuditEventType.ACTION_DENIED, proposal, context, Map.of("reason", result.message()));
            return result;
        }

        PolicyDecision decision = policyGate.evaluate(proposal, definition, context);
        record(AuditEventType.POLICY_EVALUATED, proposal, context, Map.of("type", decision.type().name()));
        if (decision.requiresApproval()) {
            ApprovalRequest approval = approvalGate.requestApproval(proposal, definition, context, decision);
            ActionExecutionResult result = ActionExecutionResult.pendingApproval(
                    decision.reason(),
                    Map.of("approvalId", approval.approvalId()));
            record(AuditEventType.APPROVAL_REQUESTED, proposal, context, Map.of("approvalId", approval.approvalId()));
            return result;
        }
        if (!decision.allowedToExecuteNow()) {
            ActionExecutionResult result = ActionExecutionResult.denied(decision.reason());
            record(AuditEventType.ACTION_DENIED, proposal, context, Map.of("reason", result.message()));
            return result;
        }

        ActionExecutionContext actionContext = new ActionExecutionContext(
                context,
                proposal,
                definition,
                proposal.input());
        if (decision.type() == PolicyDecisionType.REQUIRE_DRY_RUN) {
            ActionExecutionResult dryRun = executor.dryRun(actionContext);
            record(AuditEventType.DRY_RUN_COMPLETED, proposal, context, dryRun.output());
        }

        record(AuditEventType.ACTION_EXECUTION_STARTED, proposal, context, Map.of());
        try {
            ActionExecutionResult result = executor.execute(actionContext);
            record(result.terminalSuccess()
                    ? AuditEventType.ACTION_EXECUTION_COMPLETED
                    : AuditEventType.ACTION_EXECUTION_FAILED, proposal, context, result.output());
            return result;
        } catch (RuntimeException exception) {
            ActionExecutionResult result = ActionExecutionResult.failed(
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            record(AuditEventType.ACTION_EXECUTION_FAILED, proposal, context, Map.of("message", result.message()));
            return result;
        }
    }

    private void record(
            AuditEventType type,
            ActionProposal proposal,
            ExecutionContext context,
            Map<String, Object> payload) {
        AuditEvent event = AuditEvent.of(type, proposal, context, payload);
        auditSink.record(event);
        traceSink.record(event);
    }
}
