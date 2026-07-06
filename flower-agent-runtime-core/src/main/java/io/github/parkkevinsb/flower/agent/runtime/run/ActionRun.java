package io.github.parkkevinsb.flower.agent.runtime.run;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ActionOrigin;
import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.policy.PolicyDecisionType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ActionRun(
        String runId,
        String tenantId,
        String userId,
        String traceId,
        String actionId,
        String proposalId,
        String requesterId,
        ActionOrigin origin,
        Map<String, Object> input,
        String duplicateKey,
        ActionRunStatus status,
        String currentStage,
        PolicyDecisionType policyDecisionType,
        String policyReason,
        String approvalId,
        Instant dueAt,
        String attemptToken,
        ActionExecutionResult result,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public ActionRun {
        runId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId.trim();
        tenantId = normalize(tenantId);
        userId = normalize(userId);
        traceId = normalize(traceId);
        actionId = normalize(actionId);
        proposalId = normalize(proposalId);
        requesterId = normalize(requesterId);
        origin = Objects.requireNonNullElse(origin, ActionOrigin.UNKNOWN);
        input = input == null ? Map.of() : Map.copyOf(input);
        duplicateKey = normalize(duplicateKey);
        status = Objects.requireNonNullElse(status, ActionRunStatus.REQUESTED);
        currentStage = normalize(currentStage);
        policyReason = normalize(policyReason);
        approvalId = normalize(approvalId);
        attemptToken = normalize(attemptToken);
        failureReason = normalize(failureReason);
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static ActionRun requested(ActionProposal proposal, ExecutionContext context) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Instant now = Instant.now();
        return ActionRun.builder()
                .runId(context.runId())
                .tenantId(context.tenantId())
                .userId(context.userId())
                .traceId(context.traceId())
                .actionId(proposal.actionId())
                .proposalId(proposal.proposalId())
                .requesterId(proposal.requesterId())
                .origin(proposal.origin())
                .input(proposal.input())
                .duplicateKey(proposal.idempotencyKey())
                .status(ActionRunStatus.REQUESTED)
                .currentStage("")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .runId(runId)
                .tenantId(tenantId)
                .userId(userId)
                .traceId(traceId)
                .actionId(actionId)
                .proposalId(proposalId)
                .requesterId(requesterId)
                .origin(origin)
                .input(input)
                .duplicateKey(duplicateKey)
                .status(status)
                .currentStage(currentStage)
                .policyDecisionType(policyDecisionType)
                .policyReason(policyReason)
                .approvalId(approvalId)
                .dueAt(dueAt)
                .attemptToken(attemptToken)
                .result(result)
                .failureReason(failureReason)
                .createdAt(createdAt)
                .updatedAt(updatedAt);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Builder {
        private String runId;
        private String tenantId;
        private String userId;
        private String traceId;
        private String actionId;
        private String proposalId;
        private String requesterId;
        private ActionOrigin origin;
        private Map<String, Object> input;
        private String duplicateKey;
        private ActionRunStatus status;
        private String currentStage;
        private PolicyDecisionType policyDecisionType;
        private String policyReason;
        private String approvalId;
        private Instant dueAt;
        private String attemptToken;
        private ActionExecutionResult result;
        private String failureReason;
        private Instant createdAt;
        private Instant updatedAt;

        private Builder() {
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder actionId(String actionId) {
            this.actionId = actionId;
            return this;
        }

        public Builder proposalId(String proposalId) {
            this.proposalId = proposalId;
            return this;
        }

        public Builder requesterId(String requesterId) {
            this.requesterId = requesterId;
            return this;
        }

        public Builder origin(ActionOrigin origin) {
            this.origin = origin;
            return this;
        }

        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        public Builder duplicateKey(String duplicateKey) {
            this.duplicateKey = duplicateKey;
            return this;
        }

        public Builder status(ActionRunStatus status) {
            this.status = status;
            return this;
        }

        public Builder currentStage(String currentStage) {
            this.currentStage = currentStage;
            return this;
        }

        public Builder policyDecisionType(PolicyDecisionType policyDecisionType) {
            this.policyDecisionType = policyDecisionType;
            return this;
        }

        public Builder policyReason(String policyReason) {
            this.policyReason = policyReason;
            return this;
        }

        public Builder approvalId(String approvalId) {
            this.approvalId = approvalId;
            return this;
        }

        public Builder dueAt(Instant dueAt) {
            this.dueAt = dueAt;
            return this;
        }

        public Builder attemptToken(String attemptToken) {
            this.attemptToken = attemptToken;
            return this;
        }

        public Builder result(ActionExecutionResult result) {
            this.result = result;
            return this;
        }

        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public ActionRun build() {
            return new ActionRun(
                    runId,
                    tenantId,
                    userId,
                    traceId,
                    actionId,
                    proposalId,
                    requesterId,
                    origin,
                    input,
                    duplicateKey,
                    status,
                    currentStage,
                    policyDecisionType,
                    policyReason,
                    approvalId,
                    dueAt,
                    attemptToken,
                    result,
                    failureReason,
                    createdAt,
                    updatedAt);
        }
    }
}
