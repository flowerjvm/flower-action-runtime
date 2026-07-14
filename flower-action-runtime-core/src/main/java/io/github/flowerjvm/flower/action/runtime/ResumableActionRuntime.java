package io.github.flowerjvm.flower.action.runtime;


import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
public interface ResumableActionRuntime extends ActionRuntime {
    ActionExecutionResult resume(String runId, ApprovalDecision decision);
}
