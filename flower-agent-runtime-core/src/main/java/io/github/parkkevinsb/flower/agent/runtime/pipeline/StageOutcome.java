package io.github.parkkevinsb.flower.agent.runtime.pipeline;


import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
/**
 * Result of a single {@link ActionStage}.
 *
 * <p>{@code CONTINUE} advances to the next gate. {@code SHORT_CIRCUIT} means the stage already set a
 * terminal {@link ActionExecutionResult} and remaining gates must be skipped; the finalize stage still runs.</p>
 */
public enum StageOutcome {
    CONTINUE,
    SHORT_CIRCUIT
}
