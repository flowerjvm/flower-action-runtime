package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ApprovalRequest;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.PolicyDecision;
import io.github.parkkevinsb.flower.core.step.Step;
import io.github.parkkevinsb.flower.core.step.StepContext;
import io.github.parkkevinsb.flower.core.step.StepResult;

import java.util.Map;

final class EvaluatePolicyStep extends Step {
    private final FlowActionExecutionSession session;

    EvaluatePolicyStep(FlowActionExecutionSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        PolicyDecision decision = session.policyGate()
                .evaluate(session.proposal(), session.definition(), session.context());
        session.policyDecision(decision);
        session.record(AuditEventType.POLICY_EVALUATED, Map.of("type", decision.type().name()));
        if (decision.requiresApproval()) {
            ApprovalRequest approval = session.approvalGate()
                    .requestApproval(session.proposal(), session.definition(), session.context(), decision);
            session.result(ActionExecutionResult.pendingApproval(
                    decision.reason(),
                    Map.of("approvalId", approval.approvalId())));
            session.record(AuditEventType.APPROVAL_REQUESTED, Map.of("approvalId", approval.approvalId()));
            return StepResult.goTo("record-result");
        }
        if (!decision.allowedToExecuteNow()) {
            ActionExecutionResult result = ActionExecutionResult.denied(decision.reason());
            session.result(result);
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StepResult.goTo("record-result");
        }
        return StepResult.done();
    }
}
