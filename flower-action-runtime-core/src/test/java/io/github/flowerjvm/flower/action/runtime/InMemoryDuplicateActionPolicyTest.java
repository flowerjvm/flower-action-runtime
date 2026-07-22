package io.github.flowerjvm.flower.action.runtime;

import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecisionType;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDuplicateActionPolicyTest {
    @Test
    void sameIdempotencyKeyIsIndependentAcrossTenants() {
        InMemoryDuplicateActionPolicy policy = new InMemoryDuplicateActionPolicy();
        ActionProposal tenantOneProposal = proposal("proposal-1", "maintenance.run", "shared-key");
        ActionProposal tenantTwoProposal = proposal("proposal-2", "maintenance.run", "shared-key");
        ExecutionContext tenantOne = context("tenant-1", "run-1");
        ExecutionContext tenantTwo = context("tenant-2", "run-2");

        assertThat(policy.reserve(tenantOneProposal, tenantOne).type())
                .isEqualTo(DuplicateActionDecisionType.ACCEPT);
        assertThat(policy.reserve(tenantTwoProposal, tenantTwo).type())
                .isEqualTo(DuplicateActionDecisionType.ACCEPT);

        ActionExecutionResult tenantOneResult = ActionExecutionResult.succeeded(Map.of("tenant", "one"));
        ActionExecutionResult tenantTwoResult = ActionExecutionResult.succeeded(Map.of("tenant", "two"));
        policy.complete(tenantOneProposal, tenantOne, tenantOneResult);
        policy.complete(tenantTwoProposal, tenantTwo, tenantTwoResult);

        assertThat(policy.reserve(proposal("proposal-3", "maintenance.run", "shared-key"), tenantOne)
                .existingResult()).isEqualTo(tenantOneResult);
        assertThat(policy.reserve(proposal("proposal-4", "maintenance.run", "shared-key"), tenantTwo)
                .existingResult()).isEqualTo(tenantTwoResult);
    }

    @Test
    void sameIdempotencyKeyIsIndependentAcrossActions() {
        InMemoryDuplicateActionPolicy policy = new InMemoryDuplicateActionPolicy();
        ExecutionContext context = context("tenant-1", "run-1");

        assertThat(policy.reserve(proposal("proposal-1", "cache.clear", "shared-key"), context).type())
                .isEqualTo(DuplicateActionDecisionType.ACCEPT);
        assertThat(policy.reserve(proposal("proposal-2", "vpn.rotate", "shared-key"), context).type())
                .isEqualTo(DuplicateActionDecisionType.ACCEPT);
    }

    @Test
    void sameTenantActionAndIdempotencyKeyStillRejectsConcurrentDuplicate() {
        InMemoryDuplicateActionPolicy policy = new InMemoryDuplicateActionPolicy();
        ExecutionContext context = context("tenant-1", "run-1");

        assertThat(policy.reserve(proposal("proposal-1", "cache.clear", "shared-key"), context).type())
                .isEqualTo(DuplicateActionDecisionType.ACCEPT);
        assertThat(policy.reserve(proposal("proposal-2", "cache.clear", "shared-key"), context).type())
                .isEqualTo(DuplicateActionDecisionType.REJECT);
    }

    private static ActionProposal proposal(String proposalId, String actionId, String idempotencyKey) {
        return ActionProposal.builder(actionId)
                .proposalId(proposalId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("service-1")
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private static ExecutionContext context(String tenantId, String runId) {
        return new ExecutionContext(tenantId, "user-1", runId, runId + "-trace", Map.of());
    }
}
