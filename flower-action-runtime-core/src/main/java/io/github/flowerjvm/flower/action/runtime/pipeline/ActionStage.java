package io.github.flowerjvm.flower.action.runtime.pipeline;

/**
 * One step of the controlled action pipeline.
 *
 * <p>A stage reads and mutates the shared {@link ActionExecutionSession}. It is backend-neutral: the direct
 * runtime runs stages in a loop, and a Flower Flow backend wraps each stage in a Step. The stage list is the
 * single source of truth for pipeline order, branching, and audit payloads.</p>
 */
@FunctionalInterface
public interface ActionStage {
    StageOutcome execute(ActionExecutionSession session);
}
