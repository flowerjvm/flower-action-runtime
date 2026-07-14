package io.github.flowerjvm.flower.action.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runtime context for a controlled action request.
 *
 * <p>The {@code metadata} map may carry host-provided policy input without changing core APIs. Recommended keys are
 * {@code actor.roles}, {@code actor.permissions}, {@code resource.type}, {@code resource.id}, and
 * {@code resource.attributes}. The host application remains responsible for the trustworthiness of those values.</p>
 */
public record ExecutionContext(
        String tenantId,
        String userId,
        String runId,
        String traceId,
        Map<String, Object> metadata) {

    public ExecutionContext {
        tenantId = normalize(tenantId);
        userId = normalize(userId);
        runId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId.trim();
        traceId = traceId == null || traceId.isBlank() ? runId : traceId.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ExecutionContext of(String tenantId, String userId) {
        return new ExecutionContext(tenantId, userId, null, null, Map.of());
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
