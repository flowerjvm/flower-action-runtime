package io.github.flowerjvm.flower.action.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionProposalTest {
    @Test
    void builderCreatesHostScopedProposalWithoutCanonicalConstructorNoise() {
        ActionProposal proposal = ActionProposal.builder("document.publish")
                .proposalId("proposal-1")
                .requesterId("planner-1")
                .requestChannel(ActionRequestChannel.COMMAND)
                .proposerType(ActionProposerType.AI_PLANNER)
                .reason("Publish the approved document")
                .confidence(0.9d)
                .input(Map.of("documentId", 42L))
                .idempotencyKey("publish-42")
                .metadata(Map.of("source", "maintenance-agent"))
                .build();

        assertThat(proposal.proposalId()).isEqualTo("proposal-1");
        assertThat(proposal.actionId()).isEqualTo("document.publish");
        assertThat(proposal.requestChannel()).isEqualTo(ActionRequestChannel.COMMAND);
        assertThat(proposal.proposerType()).isEqualTo(ActionProposerType.AI_PLANNER);
        assertThat(proposal.input()).containsEntry("documentId", 42L);
        assertThat(proposal.idempotencyKey()).isEqualTo("publish-42");
    }

    @Test
    void toBuilderPreservesProposalWhileAllowingOneHostOverride() {
        ActionProposal original = ActionProposal.userFrom(
                ActionRequestChannel.UI,
                "report.review",
                Map.of("reportId", 7L),
                "user-1");

        ActionProposal retried = original.toBuilder()
                .proposalId("proposal-retry")
                .idempotencyKey("review-7")
                .build();

        assertThat(retried.actionId()).isEqualTo(original.actionId());
        assertThat(retried.input()).isEqualTo(original.input());
        assertThat(retried.requestChannel()).isEqualTo(ActionRequestChannel.UI);
        assertThat(retried.proposerType()).isEqualTo(ActionProposerType.USER);
        assertThat(retried.proposalId()).isEqualTo("proposal-retry");
        assertThat(retried.idempotencyKey()).isEqualTo("review-7");
    }

    @Test
    void confidenceRejectsNonFiniteValues() {
        assertThatThrownBy(() -> ActionProposal.builder("report.review")
                .confidence(Double.NaN)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite value");

        assertThatThrownBy(() -> ActionProposal.builder("report.review")
                .confidence(Double.POSITIVE_INFINITY)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite value");
    }
}
