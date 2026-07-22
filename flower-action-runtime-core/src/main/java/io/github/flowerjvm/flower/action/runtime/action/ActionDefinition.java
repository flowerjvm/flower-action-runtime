package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Declares an action the runtime is allowed to execute through a controlled pipeline. */
public record ActionDefinition(
        String actionId,
        String title,
        String description,
        ActionEffect effect,
        ActionRiskLevel riskLevel,
        Set<ActionRequestChannel> allowedRequestChannels,
        Set<ActionProposerType> allowedProposerTypes,
        Set<String> requiredPermissions,
        boolean dryRunSupported,
        boolean approvalRequiredByDefault,
        boolean auditRequired,
        String inputSchemaId,
        String outputSchemaId,
        Map<String, Object> metadata) {

    private static final Set<ActionRequestChannel> DEFAULT_REQUEST_CHANNELS = Set.of(
            ActionRequestChannel.UI,
            ActionRequestChannel.API,
            ActionRequestChannel.CLI,
            ActionRequestChannel.COMMAND);
    private static final Set<ActionProposerType> DEFAULT_PROPOSER_TYPES = Set.of(ActionProposerType.USER);

    public ActionDefinition {
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("actionId must not be blank");
        }
        actionId = actionId.trim();
        title = title == null ? actionId : title.trim();
        description = description == null ? "" : description.trim();
        effect = Objects.requireNonNullElse(effect, ActionEffect.READ_ONLY);
        riskLevel = Objects.requireNonNullElse(riskLevel, ActionRiskLevel.LOW);
        allowedRequestChannels = allowedRequestChannels == null || allowedRequestChannels.isEmpty()
                ? DEFAULT_REQUEST_CHANNELS
                : Set.copyOf(allowedRequestChannels);
        allowedProposerTypes = allowedProposerTypes == null || allowedProposerTypes.isEmpty()
                ? DEFAULT_PROPOSER_TYPES
                : Set.copyOf(allowedProposerTypes);
        requiredPermissions = requiredPermissions == null ? Set.of() : Set.copyOf(requiredPermissions);
        inputSchemaId = inputSchemaId == null ? "" : inputSchemaId.trim();
        outputSchemaId = outputSchemaId == null ? "" : outputSchemaId.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean allowsRequestChannel(ActionRequestChannel requestChannel) {
        return allowedRequestChannels.contains(
                Objects.requireNonNullElse(requestChannel, ActionRequestChannel.UNKNOWN));
    }

    public boolean allowsProposerType(ActionProposerType proposerType) {
        return allowedProposerTypes.contains(
                Objects.requireNonNullElse(proposerType, ActionProposerType.UNKNOWN));
    }
}
