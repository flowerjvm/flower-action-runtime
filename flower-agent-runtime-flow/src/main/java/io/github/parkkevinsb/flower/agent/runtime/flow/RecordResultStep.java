package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionStatus;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.DuplicateActionDecisionType;
import io.github.parkkevinsb.flower.core.step.Step;
import io.github.parkkevinsb.flower.core.step.StepContext;
import io.github.parkkevinsb.flower.core.step.StepResult;

final class RecordResultStep extends Step {
    private final FlowActionExecutionSession session;

    RecordResultStep(FlowActionExecutionSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        ActionExecutionResult result = session.result();
        if (result.status() == ActionExecutionStatus.SUCCEEDED) {
            session.record(AuditEventType.ACTION_EXECUTION_COMPLETED, result.output());
        } else if (result.status() == ActionExecutionStatus.FAILED) {
            session.record(AuditEventType.ACTION_EXECUTION_FAILED, result.output());
        }
        if (session.duplicateDecision() == null
                || session.duplicateDecision().type() == DuplicateActionDecisionType.ACCEPT) {
            session.duplicateActionPolicy().complete(session.proposal(), result);
        }
        return StepResult.finish();
    }
}
