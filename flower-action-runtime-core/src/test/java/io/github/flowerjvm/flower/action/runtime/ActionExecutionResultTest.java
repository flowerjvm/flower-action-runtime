package io.github.flowerjvm.flower.action.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionExecutionResultTest {
    @Test
    void unknownFailureDefaultsToManualReview() {
        ActionExecutionResult result = ActionExecutionResult.failed(
                "ACTION_EXECUTION_EXCEPTION",
                "The external side-effect outcome is unknown.");

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(result.retryDisposition()).isEqualTo(RetryDisposition.MANUAL_REVIEW);
    }

    @Test
    void explicitFailureFactoriesDeclareRetryPolicy() {
        assertThat(ActionExecutionResult.retryableFailure("TEMPORARY", "Retry later").retryDisposition())
                .isEqualTo(RetryDisposition.AFTER_BACKOFF);
        assertThat(ActionExecutionResult.correctableFailure("CONFIG", "Correct configuration").retryDisposition())
                .isEqualTo(RetryDisposition.AFTER_CORRECTION);
        assertThat(ActionExecutionResult.permanentFailure("UNSUPPORTED", "Unsupported").retryDisposition())
                .isEqualTo(RetryDisposition.NEVER);
        assertThat(ActionExecutionResult.manualReviewFailure("UNKNOWN", "Review required").retryDisposition())
                .isEqualTo(RetryDisposition.MANUAL_REVIEW);
    }

    @Test
    void compatibilityConstructorUsesConservativeFailureDefault() {
        ActionExecutionResult result = new ActionExecutionResult(
                ActionExecutionStatus.FAILED,
                "Legacy failure",
                null);

        assertThat(result.retryDisposition()).isEqualTo(RetryDisposition.MANUAL_REVIEW);
    }

    @Test
    void canonicalConstructorUsesStatusDefaultWhenRetryDispositionIsMissing() {
        ActionExecutionResult failed = new ActionExecutionResult(
                ActionExecutionStatus.FAILED,
                "ACTION_FAILED",
                "Unknown outcome",
                null,
                null);
        ActionExecutionResult pending = new ActionExecutionResult(
                ActionExecutionStatus.PENDING_APPROVAL,
                "APPROVAL_REQUIRED",
                "Approve first",
                null,
                null);

        assertThat(failed.retryDisposition()).isEqualTo(RetryDisposition.MANUAL_REVIEW);
        assertThat(pending.retryDisposition()).isEqualTo(RetryDisposition.AFTER_APPROVAL);
    }
}
