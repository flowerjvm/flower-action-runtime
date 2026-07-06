package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.PolicyDecisionType;
import io.github.parkkevinsb.flower.core.step.Step;
import io.github.parkkevinsb.flower.core.step.StepContext;
import io.github.parkkevinsb.flower.core.step.StepResult;

import java.util.Map;

final class ExecuteActionStep extends Step {
    private final FlowActionExecutionSession session;

    ExecuteActionStep(FlowActionExecutionSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        ActionExecutionContext actionContext = new ActionExecutionContext(
                session.context(),
                session.proposal(),
                session.definition(),
                session.proposal().input());
        if (session.policyDecision().type() == PolicyDecisionType.REQUIRE_DRY_RUN) {
            ActionExecutionResult dryRun = session.executor().dryRun(actionContext);
            session.record(AuditEventType.DRY_RUN_COMPLETED, dryRun.output());
        }
        session.record(AuditEventType.ACTION_EXECUTION_STARTED, Map.of());
        try {
            session.result(session.executor().execute(actionContext));
        } catch (RuntimeException exception) {
            session.result(ActionExecutionResult.failed(
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
        }
        return StepResult.done();
    }
}
