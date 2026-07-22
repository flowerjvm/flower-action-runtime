package io.github.flowerjvm.flower.action.runtime;

import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecisionType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPolicyGateTest {
    @Test
    void aiPlannerWriteUsesExplicitProposerType() {
        ActionProposal proposal = ActionProposal.builder("document.publish")
                .requestChannel(ActionRequestChannel.COMMAND)
                .proposerType(ActionProposerType.AI_PLANNER)
                .requesterId("planner-1")
                .build();
        ActionDefinition definition = new ActionDefinition(
                "document.publish",
                "Publish document",
                "Publishes a document",
                ActionEffect.WRITE,
                ActionRiskLevel.LOW,
                Set.of(ActionRequestChannel.COMMAND),
                Set.of(ActionProposerType.AI_PLANNER),
                Set.of(),
                false,
                false,
                true,
                "",
                "",
                Map.of());

        var decision = new DefaultPolicyGate().evaluate(
                proposal,
                definition,
                new ExecutionContext("tenant-1", "user-1", "run-1", "trace-1", Map.of()));

        assertThat(decision.type()).isEqualTo(PolicyDecisionType.REQUIRE_APPROVAL);
    }

    @Test
    void disallowedRequestChannelIsDeniedBeforeEffectPolicy() {
        ActionProposal proposal = ActionProposal.builder("document.publish")
                .requestChannel(ActionRequestChannel.MCP)
                .proposerType(ActionProposerType.USER)
                .requesterId("user-1")
                .build();
        ActionDefinition definition = new ActionDefinition(
                "document.publish",
                "Publish document",
                "Publishes a document",
                ActionEffect.WRITE,
                ActionRiskLevel.LOW,
                Set.of(ActionRequestChannel.UI),
                Set.of(ActionProposerType.USER),
                Set.of(),
                false,
                false,
                true,
                "",
                "",
                Map.of());

        var decision = new DefaultPolicyGate().evaluate(
                proposal,
                definition,
                new ExecutionContext("tenant-1", "user-1", "run-1", "trace-1", Map.of()));

        assertThat(decision.type()).isEqualTo(PolicyDecisionType.DENY);
        assertThat(decision.reason()).contains("request channel");
    }
}
