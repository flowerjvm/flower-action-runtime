package io.github.flowerjvm.flower.action.runtime;

/**
 * Kind of actor that proposed an action. This is intentionally separate from the request channel and execution
 * principal.
 */
public enum ActionProposerType {
    USER,
    AI_PLANNER,
    SYSTEM,
    SERVICE,
    UNKNOWN
}
