package io.github.parkkevinsb.flower.agent.runtime;


import io.github.parkkevinsb.flower.agent.runtime.approval.ApprovalDecision;
public interface ResumableActionRuntime extends ActionRuntime {
    ActionExecutionResult resume(String runId, ApprovalDecision decision);
}
