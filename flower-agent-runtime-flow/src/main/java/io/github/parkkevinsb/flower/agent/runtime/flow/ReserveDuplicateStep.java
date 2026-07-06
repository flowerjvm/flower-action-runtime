package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.DuplicateActionDecision;
import io.github.parkkevinsb.flower.agent.runtime.DuplicateActionDecisionType;
import io.github.parkkevinsb.flower.core.step.Step;
import io.github.parkkevinsb.flower.core.step.StepContext;
import io.github.parkkevinsb.flower.core.step.StepResult;

import java.util.Map;

final class ReserveDuplicateStep extends Step {
    private final FlowActionExecutionSession session;

    ReserveDuplicateStep(FlowActionExecutionSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        DuplicateActionDecision decision =
                session.duplicateActionPolicy().reserve(session.proposal(), session.context());
        session.duplicateDecision(decision);
        if (decision.type() == DuplicateActionDecisionType.ACCEPT) {
            return StepResult.done();
        }
        session.record(AuditEventType.ACTION_DUPLICATE, Map.of("decision", decision.type().name()));
        if (decision.type() == DuplicateActionDecisionType.RETURN_EXISTING) {
            session.result(decision.existingResult());
        } else {
            session.result(ActionExecutionResult.denied(decision.reason()));
        }
        return StepResult.goTo("record-result");
    }
}
