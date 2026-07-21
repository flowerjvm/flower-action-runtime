package io.github.flowerjvm.flower.action.runtime;

/**
 * Transport or entry-point through which an action request reached the runtime.
 */
public enum ActionRequestChannel {
    UI,
    API,
    CLI,
    COMMAND,
    MCP,
    SCHEDULER,
    INTERNAL,
    RECOVERY,
    UNKNOWN
}
