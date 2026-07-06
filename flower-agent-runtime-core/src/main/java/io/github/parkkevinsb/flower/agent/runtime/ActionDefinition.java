package io.github.parkkevinsb.flower.agent.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ActionDefinition(
        String actionId,
        String title,
        String description,
        ActionEffect effect,
        ActionRiskLevel riskLevel,
        Set<ActionOrigin> allowedOrigins,
        Set<String> requiredPermissions,
        boolean dryRunSupported,
        boolean approvalRequiredByDefault,
        boolean auditRequired,
        String inputSchemaId,
        String outputSchemaId,
        Map<String, Object> metadata) {

    public ActionDefinition {
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("actionId must not be blank");
        }
        actionId = actionId.trim();
        title = title == null ? actionId : title.trim();
        description = description == null ? "" : description.trim();
        effect = Objects.requireNonNullElse(effect, ActionEffect.READ_ONLY);
        riskLevel = Objects.requireNonNullElse(riskLevel, ActionRiskLevel.LOW);
        allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? Set.of(ActionOrigin.USER, ActionOrigin.UI, ActionOrigin.API)
                : Set.copyOf(allowedOrigins);
        requiredPermissions = requiredPermissions == null ? Set.of() : Set.copyOf(requiredPermissions);
        inputSchemaId = inputSchemaId == null ? "" : inputSchemaId.trim();
        outputSchemaId = outputSchemaId == null ? "" : outputSchemaId.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean allowsOrigin(ActionOrigin origin) {
        return allowedOrigins.contains(Objects.requireNonNullElse(origin, ActionOrigin.UNKNOWN));
    }
}
