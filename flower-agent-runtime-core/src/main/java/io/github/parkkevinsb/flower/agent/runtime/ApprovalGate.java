package io.github.parkkevinsb.flower.agent.runtime;

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
