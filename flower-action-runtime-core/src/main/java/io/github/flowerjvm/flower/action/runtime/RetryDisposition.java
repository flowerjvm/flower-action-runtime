package io.github.flowerjvm.flower.action.runtime;

/**
 * Machine-readable guidance for callers deciding whether an action may be attempted again.
 */
public enum RetryDisposition {
    NEVER,
    AFTER_BACKOFF,
    AFTER_CORRECTION,
    AFTER_APPROVAL,
    MANUAL_REVIEW
}
