package io.github.flowerjvm.flower.action.runtime.approval;


import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
public interface ApprovalGate {
    ApprovalRequest requestApproval(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context,
            PolicyDecision policyDecision);

    static ApprovalGate unsupported() {
        return (proposal, definition, context, policyDecision) ->
                ApprovalRequest.pending(proposal.proposalId(), "approval-gate-not-configured");
    }
}
