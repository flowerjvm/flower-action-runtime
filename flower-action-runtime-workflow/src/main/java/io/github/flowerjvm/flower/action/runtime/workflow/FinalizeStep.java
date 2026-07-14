package io.github.flowerjvm.flower.action.runtime.workflow;

import io.github.flowerjvm.flower.action.runtime.pipeline.ActionExecutionSession;
import io.github.flowerjvm.flower.action.runtime.pipeline.ActionPipeline;
import io.github.flowerjvm.flower.action.runtime.pipeline.ActionStage;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

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
