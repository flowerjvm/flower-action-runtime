package io.github.flowerjvm.flower.action.runtime.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionOrigin;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecisionType;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public final class JdbcRunStore implements RunStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String COLUMNS = String.join(", ",
            "run_id",
            "tenant_id",
            "user_id",
            "trace_id",
            "context_metadata_json",
            "action_id",
            "proposal_id",
            "requester_id",
            "origin",
            "proposal_reason",
            "proposal_confidence",
            "proposal_metadata_json",
            "input_json",
            "duplicate_key",
            "status",
            "current_stage",
            "policy_decision_type",
            "policy_reason",
            "approval_id",
            "due_at",
            "attempt_token",
            "result_status",
            "result_message",
            "result_output_json",
            "failure_reason",
            "created_at",
            "updated_at");
    private static final String INSERT_SQL = "INSERT INTO action_run (" + COLUMNS + ") VALUES ("
            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_SQL = """
            UPDATE action_run
            SET tenant_id = ?,
                user_id = ?,
                trace_id = ?,
                context_metadata_json = ?,
                action_id = ?,
                proposal_id = ?,
                requester_id = ?,
                origin = ?,
                proposal_reason = ?,
                proposal_confidence = ?,
                proposal_metadata_json = ?,
                input_json = ?,
                duplicate_key = ?,
                status = ?,
                current_stage = ?,
                policy_decision_type = ?,
                policy_reason = ?,
                approval_id = ?,
                due_at = ?,
                attempt_token = ?,
                result_status = ?,
                result_message = ?,
                result_output_json = ?,
                failure_reason = ?,
                created_at = ?,
                updated_at = ?
            WHERE run_id = ?
            """;
    private static final String FIND_SQL = "SELECT " + COLUMNS + " FROM action_run WHERE run_id = ?";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcRunStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public static JdbcRunStore create(DataSource dataSource) {
        return new JdbcRunStore(dataSource, new ObjectMapper());
    }

    @Override
    public ActionRun create(ActionRun run) {
        Objects.requireNonNull(run, "run must not be null");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindInsert(statement, run);
            statement.executeUpdate();
            return run;
        } catch (SQLException ex) {
            throw new RunStoreException("Failed to create action run " + run.runId(), ex);
        }
    }

    @Override
    public Optional<ActionRun> find(String runId) {
        String normalizedRunId = normalize(runId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, normalizedRunId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRun(resultSet));
            }
        } catch (SQLException ex) {
            throw new RunStoreException("Failed to find action run " + normalizedRunId, ex);
        }
    }

    @Override
    public void update(ActionRun run) {
        Objects.requireNonNull(run, "run must not be null");
        try (Connection connection = dataSource.getConnection()) {
            int updated = update(connection, run);
            if (updated == 0) {
                insertAfterMissingUpdate(connection, run);
            }
        } catch (SQLException ex) {
            throw new RunStoreException("Failed to update action run " + run.runId(), ex);
        }
    }

    @Override
    public List<ActionRun> findResumable(String tenantId) {
        String normalizedTenantId = normalize(tenantId);
        List<String> nonTerminalStatuses = Arrays.stream(ActionRunStatus.values())
                .filter(status -> !status.isTerminal())
                .map(Enum::name)
                .toList();
        if (nonTerminalStatuses.isEmpty()) {
            return List.of();
        }
        String placeholders = nonTerminalStatuses.stream()
                .map(status -> "?")
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + COLUMNS
                + " FROM action_run WHERE tenant_id = ? AND status IN (" + placeholders + ")"
                + " ORDER BY updated_at ASC, run_id ASC";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedTenantId);
            for (int i = 0; i < nonTerminalStatuses.size(); i++) {
                statement.setString(i + 2, nonTerminalStatuses.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<ActionRun> runs = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    runs.add(mapRun(resultSet));
                }
                return List.copyOf(runs);
            }
        } catch (SQLException ex) {
            throw new RunStoreException("Failed to find resumable action runs for tenant " + normalizedTenantId, ex);
        }
    }

    private int update(Connection connection, ActionRun run) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            bindUpdate(statement, run);
            return statement.executeUpdate();
        }
    }

    private void insertAfterMissingUpdate(Connection connection, ActionRun run) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindInsert(statement, run);
            statement.executeUpdate();
        } catch (SQLException insertFailure) {
            int updated = update(connection, run);
            if (updated == 0) {
                throw insertFailure;
            }
        }
    }

    private void bindInsert(PreparedStatement statement, ActionRun run) throws SQLException {
        int index = 1;
        statement.setString(index++, run.runId());
        index = bindMutableColumns(statement, index, run);
        if (index != 28) {
            throw new IllegalStateException("Unexpected insert bind count: " + index);
        }
    }

    private void bindUpdate(PreparedStatement statement, ActionRun run) throws SQLException {
        int index = bindMutableColumns(statement, 1, run);
        statement.setString(index++, run.runId());
        if (index != 28) {
            throw new IllegalStateException("Unexpected update bind count: " + index);
        }
    }

    private int bindMutableColumns(PreparedStatement statement, int index, ActionRun run) throws SQLException {
        statement.setString(index++, run.tenantId());
        statement.setString(index++, run.userId());
        statement.setString(index++, run.traceId());
        statement.setString(index++, writeJson(run.contextMetadata()));
        statement.setString(index++, run.actionId());
        statement.setString(index++, run.proposalId());
        statement.setString(index++, run.requesterId());
        statement.setString(index++, run.origin().name());
        statement.setString(index++, run.proposalReason());
        statement.setDouble(index++, run.proposalConfidence());
        statement.setString(index++, writeJson(run.proposalMetadata()));
        statement.setString(index++, writeJson(run.input()));
        statement.setString(index++, run.duplicateKey());
        statement.setString(index++, run.status().name());
        statement.setString(index++, run.currentStage());
        setNullableEnum(statement, index++, run.policyDecisionType());
        statement.setString(index++, run.policyReason());
        statement.setString(index++, run.approvalId());
        setNullableInstant(statement, index++, run.dueAt());
        statement.setString(index++, run.attemptToken());
        ActionExecutionResult result = run.result();
        setNullableEnum(statement, index++, result == null ? null : result.status());
        statement.setString(index++, result == null ? "" : result.message());
        statement.setString(index++, result == null ? "{}" : writeJson(result.output()));
        statement.setString(index++, run.failureReason());
        statement.setLong(index++, run.createdAt().toEpochMilli());
        statement.setLong(index++, run.updatedAt().toEpochMilli());
        return index;
    }

    private ActionRun mapRun(ResultSet resultSet) throws SQLException {
        return ActionRun.builder()
                .runId(resultSet.getString("run_id"))
                .tenantId(resultSet.getString("tenant_id"))
                .userId(resultSet.getString("user_id"))
                .traceId(resultSet.getString("trace_id"))
                .contextMetadata(readMap(resultSet.getString("context_metadata_json")))
                .actionId(resultSet.getString("action_id"))
                .proposalId(resultSet.getString("proposal_id"))
                .requesterId(resultSet.getString("requester_id"))
                .origin(ActionOrigin.valueOf(resultSet.getString("origin")))
                .proposalReason(resultSet.getString("proposal_reason"))
                .proposalConfidence(resultSet.getDouble("proposal_confidence"))
                .proposalMetadata(readMap(resultSet.getString("proposal_metadata_json")))
                .input(readMap(resultSet.getString("input_json")))
                .duplicateKey(resultSet.getString("duplicate_key"))
                .status(ActionRunStatus.valueOf(resultSet.getString("status")))
                .currentStage(resultSet.getString("current_stage"))
                .policyDecisionType(readPolicyDecisionType(resultSet))
                .policyReason(resultSet.getString("policy_reason"))
                .approvalId(resultSet.getString("approval_id"))
                .dueAt(readNullableInstant(resultSet, "due_at"))
                .attemptToken(resultSet.getString("attempt_token"))
                .result(readResult(resultSet))
                .failureReason(resultSet.getString("failure_reason"))
                .createdAt(Instant.ofEpochMilli(resultSet.getLong("created_at")))
                .updatedAt(Instant.ofEpochMilli(resultSet.getLong("updated_at")))
                .build();
    }

    private PolicyDecisionType readPolicyDecisionType(ResultSet resultSet) throws SQLException {
        String value = resultSet.getString("policy_decision_type");
        return value == null || value.isBlank() ? null : PolicyDecisionType.valueOf(value);
    }

    private ActionExecutionResult readResult(ResultSet resultSet) throws SQLException {
        String status = resultSet.getString("result_status");
        if (status == null || status.isBlank()) {
            return null;
        }
        return new ActionExecutionResult(
                ActionExecutionStatus.valueOf(status),
                resultSet.getString("result_message"),
                readMap(resultSet.getString("result_output_json")));
    }

    private Instant readNullableInstant(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private void setNullableEnum(PreparedStatement statement, int index, Enum<?> value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
            return;
        }
        statement.setString(index, value.name());
    }

    private void setNullableInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
            return;
        }
        statement.setLong(index, value.toEpochMilli());
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new RunStoreException("Failed to serialize action run JSON", ex);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new RunStoreException("Failed to deserialize action run JSON", ex);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
