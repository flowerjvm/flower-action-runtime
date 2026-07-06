package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.core.step.Step;
import io.github.parkkevinsb.flower.core.step.StepContext;
import io.github.parkkevinsb.flower.core.step.StepResult;

import java.util.Map;

final class RecordProposalStep extends Step {
    private final FlowActionExecutionSession session;

    RecordProposalStep(FlowActionExecutionSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        session.record(AuditEventType.ACTION_PROPOSED, Map.of("origin", session.proposal().origin().name()));
        return StepResult.done();
    }
}
