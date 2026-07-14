package io.github.flowerjvm.flower.action.runtime.eventloop;

import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.eventloop.event.EventSignal;
import io.github.flowerjvm.flower.eventloop.step.AwaitCondition;
import io.github.flowerjvm.flower.eventloop.step.EventStep;
import io.github.flowerjvm.flower.eventloop.step.EventStepContext;
import io.github.flowerjvm.flower.eventloop.step.EventStepResult;

import java.util.Objects;

final class ApprovalWaitStep extends EventStep {
    private final String runId;
    private final String approvalId;
    private final long remainingMillis;
    private final DefaultActionRuntime delegate;

    ApprovalWaitStep(String runId, String approvalId, long remainingMillis, DefaultActionRuntime delegate) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        this.runId = runId.trim();
        this.approvalId = approvalId.trim();
        this.remainingMillis = Math.max(0L, remainingMillis);
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    protected EventStepResult onEnter(EventStepContext ctx) {
        return EventStepResult.await(
                AwaitCondition.signal(EventLoopActionRuntime.APPROVAL_SIGNAL, approvalId),
                AwaitCondition.deadlineIn(remainingMillis));
    }

    @Override
    protected EventStepResult onEvent(EventStepContext ctx, Object event) {
        EventSignal signal = (EventSignal) event;
        delegate.resume(runId, signal.payload(ApprovalDecision.class));
        return EventStepResult.finish();
    }

    @Override
    protected EventStepResult onTimeout(EventStepContext ctx) {
        delegate.resume(runId, ApprovalDecision.expired(approvalId));
        return EventStepResult.finish();
    }
}
