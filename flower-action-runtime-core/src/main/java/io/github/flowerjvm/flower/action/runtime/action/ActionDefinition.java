package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionOrigin;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Declares an action the runtime is allowed to execute through a controlled pipeline.
 *
 * <p>{@code effect}, {@code riskLevel}, {@code allowedOrigins}, {@code dryRunSupported}, and
 * {@code approvalRequiredByDefault} are read by the core pipeline or default policy. {@code auditRequired} is a
 * contract for host/runtime configuration: core emits audit events for every action, while production hosts should
 * provide a durable {@code AuditSink} for actions that require audit retention.</p>
 *
 * <p>{@code requiredPermissions} is a policy input contract. The default core policy gate does not enforce it because
 * core has no actor permission source. Host-specific or external {@code PolicyGate} implementations should compare it
 * with trusted actor permissions supplied by the host application.</p>
 */
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
