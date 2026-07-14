package io.github.flowerjvm.flower.action.runtime.workflow;

import io.github.flowerjvm.flower.action.runtime.pipeline.ActionPipeline;
import io.github.flowerjvm.flower.action.runtime.pipeline.ActionExecutionSession;
import io.github.flowerjvm.flower.action.runtime.pipeline.ActionStage;
import io.github.flowerjvm.flower.action.runtime.pipeline.StageOutcome;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

/**
 * Flow Step that runs one shared {@link ActionStage}.
 *
 * <p>It holds no governance logic of its own: it delegates to the core stage and translates a short-circuit
 * into a jump to the finalize step. All pipeline behavior lives in {@code ActionPipeline}.</p>
 */
final class StageStep extends Step {
    private final ActionExecutionSession session;
    private final ActionStage stage;
    private final String finalizeStepName;

    StageStep(ActionExecutionSession session, ActionStage stage, String finalizeStepName) {
        this.session = session;
        this.stage = stage;
        this.finalizeStepName = finalizeStepName;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        return stage.execute(session) == StageOutcome.SHORT_CIRCUIT
                ? StepResult.goTo(finalizeStepName)
                : StepResult.done();
    }
}
