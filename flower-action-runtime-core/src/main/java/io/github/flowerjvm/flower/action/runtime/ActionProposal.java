package io.github.flowerjvm.flower.action.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Proposed business action and its loose input payload.
 *
 * <p>The request channel identifies where the request entered, while proposer type identifies what kind of actor
 * suggested it. Neither grants authority: policy must use the trusted execution principal and tenant from
 * {@link ExecutionContext}.</p>
 *
 * <p>The {@code input} and {@code metadata} maps are intentional MVP flexibility. Core stays free of JSON and typed
 * schema frameworks; use {@code ActionInputValidator} for validation. A typed action adapter can later translate
 * typed request objects to and from these maps at a module boundary.</p>
 */
public record ActionProposal(
        String proposalId,
        String actionId,
        ActionRequestChannel requestChannel,
        ActionProposerType proposerType,
        String requesterId,
        String reason,
        double confidence,
        Map<String, Object> input,
        String idempotencyKey,
        Map<String, Object> metadata) {

    public ActionProposal {
        proposalId = proposalId == null || proposalId.isBlank()
                ? UUID.randomUUID().toString()
                : proposalId.trim();
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("actionId must not be blank");
        }
        actionId = actionId.trim();
        requestChannel = Objects.requireNonNullElse(requestChannel, ActionRequestChannel.UNKNOWN);
        proposerType = Objects.requireNonNullElse(proposerType, ActionProposerType.UNKNOWN);
        requesterId = requesterId == null ? "" : requesterId.trim();
        reason = reason == null ? "" : reason.trim();
        if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be a finite value between 0.0 and 1.0");
        }
        input = input == null ? Map.of() : Map.copyOf(input);
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? proposalId
                : idempotencyKey.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String actionId) {
        return new Builder().actionId(actionId);
    }

    public Builder toBuilder() {
        return new Builder()
                .proposalId(proposalId)
                .actionId(actionId)
                .requestChannel(requestChannel)
                .proposerType(proposerType)
                .requesterId(requesterId)
                .reason(reason)
                .confidence(confidence)
                .input(input)
                .idempotencyKey(idempotencyKey)
                .metadata(metadata);
    }

    public static ActionProposal userFrom(
            ActionRequestChannel requestChannel,
            String actionId,
            Map<String, Object> input,
            String requesterId) {
        return builder(actionId)
                .requestChannel(requestChannel)
                .proposerType(ActionProposerType.USER)
                .requesterId(requesterId)
                .input(input)
                .build();
    }

    public static final class Builder {
        private String proposalId;
        private String actionId;
        private ActionRequestChannel requestChannel;
        private ActionProposerType proposerType;
        private String requesterId;
        private String reason;
        private double confidence = 1.0d;
        private Map<String, Object> input;
        private String idempotencyKey;
        private Map<String, Object> metadata;

        private Builder() {
        }

        public Builder proposalId(String proposalId) {
            this.proposalId = proposalId;
            return this;
        }

        public Builder actionId(String actionId) {
            this.actionId = actionId;
            return this;
        }

        public Builder requestChannel(ActionRequestChannel requestChannel) {
            this.requestChannel = requestChannel;
            return this;
        }

        public Builder proposerType(ActionProposerType proposerType) {
            this.proposerType = proposerType;
            return this;
        }

        public Builder requesterId(String requesterId) {
            this.requesterId = requesterId;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ActionProposal build() {
            return new ActionProposal(
                    proposalId,
                    actionId,
                    requestChannel,
                    proposerType,
                    requesterId,
                    reason,
                    confidence,
                    input,
                    idempotencyKey,
                    metadata);
        }
    }
}
