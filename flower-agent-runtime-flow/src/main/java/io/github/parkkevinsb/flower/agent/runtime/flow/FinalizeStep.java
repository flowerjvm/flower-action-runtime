package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionSession;
import io.github.parkkevinsb.flower.agent.runtime.ActionPipeline;
import io.github.parkkevinsb.flower.agent.runtime.ActionStage;
import io.github.parkkevinsb.flower.core.step.Step;
import io.github.parkkevinsb.flower.core.step.StepContext;
import io.github.parkkevinsb.flower.core.step.StepResult;

/**
 * Terminal Flow Step that runs the shared finalize stage and ends the flow.
 *
 * <p>It is the single convergence point every short-circuiting gate jumps to, mirroring the direct runtime's
 * always-run finalize stage.</p>
 */
final class FinalizeStep extends Step {
    private final ActionExecutionSession session;
    private final ActionStage stage;

    FinalizeStep(ActionExecutionSession session, ActionStage stage) {
        this.session = session;
        this.stage = stage;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        try {
            stage.execute(session);
        } catch (RuntimeException exception) {
            ActionPipeline.failFinalize(session, exception);
        }
        return StepResult.finish();
    }
}
