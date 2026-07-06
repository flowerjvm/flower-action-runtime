package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutor;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.core.step.Step;
import io.github.parkkevinsb.flower.core.step.StepContext;
import io.github.parkkevinsb.flower.core.step.StepResult;

import java.util.Map;

final class ResolveActionStep extends Step {
    private final FlowActionExecutionSession session;

    ResolveActionStep(FlowActionExecutionSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        ActionExecutor executor = session.registry().findExecutor(session.proposal().actionId()).orElse(null);
        if (executor == null) {
            ActionExecutionResult result = ActionExecutionResult.denied(
                    "Action is not registered: " + session.proposal().actionId());
            session.result(result);
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StepResult.goTo("record-result");
        }
        session.executor(executor);
        session.definition(executor.definition());
        session.record(AuditEventType.ACTION_RESOLVED, Map.of(
                "riskLevel", executor.definition().riskLevel().name(),
                "effect", executor.definition().effect().name()));
        return StepResult.done();
    }
}
