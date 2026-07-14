package io.github.flowerjvm.flower.action.runtime.persistence.jdbc;

import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.ActionOrigin;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.action.ActionRegistry;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecisionType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRunStoreTest {
    @Test
    void roundTripsActionRunWithAllFields() {
        JdbcRunStore store = store();
        ActionRun run = fullRun("run-round-trip")
                .build();

        store.create(run);

        assertThat(store.find(run.runId())).contains(run);
    }

    @Test
    void roundTripsNullableResultPolicyAndDueAt() {
        JdbcRunStore store = store();
        ActionRun run = fullRun("run-nullable")
                .policyDecisionType(null)
                .dueAt(null)
                .result(null)
                .build();

        store.create(run);

        ActionRun loaded = store.find(run.runId()).orElseThrow();
        assertThat(loaded.policyDecisionType()).isNull();
        assertThat(loaded.dueAt()).isNull();
        assertThat(loaded.result()).isNull();
        assertThat(loaded).isEqualTo(run);
    }

    @Test
    void updateUpsertsMissingRunAndUpdatesExistingRun() {
        DataSource dataSource = dataSource();
        JdbcRunStore store = JdbcRunStore.create(dataSource);
        ActionRun insertedByUpdate = fullRun("run-upsert")
                .status(ActionRunStatus.REQUESTED)
                .currentStage("record-proposal")
                .build();

        store.update(insertedByUpdate);

        assertThat(store.find(insertedByUpdate.runId())).contains(insertedByUpdate);
        assertThat(rowCount(dataSource)).isEqualTo(1);

        ActionRun updated = insertedByUpdate.toBuilder()
                .status(ActionRunStatus.RUNNING)
                .currentStage("execute-action")
                .updatedAt(Instant.parse("2026-01-01T00:00:10Z"))
                .build();

        store.update(updated);

        assertThat(store.find(updated.runId())).contains(updated);
        assertThat(rowCount(dataSource)).isEqualTo(1);
    }

    @Test
    void findResumableReturnsOnlyNonTerminalRunsForTenant() {
        JdbcRunStore store = store();
        ActionRun waiting = fullRun("run-waiting")
                .tenantId("tenant-a")
                .status(ActionRunStatus.WAITING_APPROVAL)
                .build();
        ActionRun running = fullRun("run-running")
                .tenantId("tenant-a")
                .status(ActionRunStatus.RUNNING)
                .build();
        ActionRun completed = fullRun("run-completed")
                .tenantId("tenant-a")
                .status(ActionRunStatus.SUCCEEDED)
                .build();
        ActionRun otherTenant = fullRun("run-other")
                .tenantId("tenant-b")
                .status(ActionRunStatus.RUNNING)
                .build();

        store.create(waiting);
        store.create(running);
        store.create(completed);
        store.create(otherTenant);

        assertThat(store.findResumable("tenant-a"))
                .extracting(ActionRun::runId)
                .containsExactlyInAnyOrder(waiting.runId(), running.runId());
    }

    @Test
    void defaultRuntimePersistsFinalAndApprovalRuns() {
        JdbcRunStore store = store();
        DefaultActionRuntime successRuntime = runtime(
                registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                store);
        ExecutionContext successContext = context("run-jdbc-success");

        ActionExecutionResult success = successRuntime.handle(
                ActionProposal.user("CreateReport", Map.of("siteId", 1), "user-1"),
                successContext);

        ActionRun successRun = store.find(successContext.runId()).orElseThrow();
        assertThat(success.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(successRun.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(successRun.result()).isEqualTo(success);

        DefaultActionRuntime approvalRuntime = runtime(
                registryOf(writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                        ActionExecutionResult.succeeded(Map.of())),
                store);
        ExecutionContext approvalContext = context("run-jdbc-approval");

        approvalRuntime.handle(
                new ActionProposal("proposal-approval", "UpdateReport", ActionOrigin.AI_PLANNER,
                        "planner", "update", 0.9d, Map.of(), null, Map.of()),
                approvalContext);

        ActionRun approvalRun = store.find(approvalContext.runId()).orElseThrow();
        assertThat(approvalRun.status()).isEqualTo(ActionRunStatus.WAITING_APPROVAL);
        assertThat(approvalRun.approvalId()).isNotBlank();
    }

    @Test
    void newStoreInstanceCanReadExistingRunsAndFindResumableRuns() {
        DataSource dataSource = dataSource();
        JdbcRunStore firstStore = JdbcRunStore.create(dataSource);
        ActionRun waiting = fullRun("run-restart-waiting")
                .tenantId("tenant-restart")
                .status(ActionRunStatus.WAITING_APPROVAL)
                .build();

        firstStore.create(waiting);

        JdbcRunStore restartedStore = JdbcRunStore.create(dataSource);
        assertThat(restartedStore.find(waiting.runId())).contains(waiting);
        assertThat(restartedStore.findResumable("tenant-restart"))
                .extracting(ActionRun::runId)
                .containsExactly(waiting.runId());
    }

    @Test
    void resumesParkedApprovalRunAfterRuntimeRestart() {
        DataSource dataSource = dataSource();
        JdbcRunStore firstStore = JdbcRunStore.create(dataSource);
        CountingExecutor firstExecutor = new CountingExecutor(
                writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                ActionExecutionResult.succeeded(Map.of("updated", true)));
        DefaultActionRuntime firstRuntime = runtime(new InMemoryActionRegistry(List.of(firstExecutor)), firstStore);
        ExecutionContext context = context("run-jdbc-resume");

        ActionExecutionResult parked = firstRuntime.handle(
                new ActionProposal("proposal-jdbc-resume", "UpdateReport", ActionOrigin.AI_PLANNER,
                        "planner", "update", 0.9d, Map.of("siteId", 1), null, Map.of()),
                context);
        ActionRun waiting = firstStore.find(context.runId()).orElseThrow();

        assertThat(parked.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(waiting.status()).isEqualTo(ActionRunStatus.WAITING_APPROVAL);
        assertThat(firstStore.findResumable("tenant-1")).extracting(ActionRun::runId).contains(context.runId());
        assertThat(firstExecutor.calls()).isZero();

        JdbcRunStore restartedStore = JdbcRunStore.create(dataSource);
        CountingExecutor restartedExecutor = new CountingExecutor(
                writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                ActionExecutionResult.succeeded(Map.of("updated", true)));
        DefaultActionRuntime restartedRuntime = runtime(
                new InMemoryActionRegistry(List.of(restartedExecutor)),
                restartedStore);

        ActionExecutionResult resumed = restartedRuntime.resume(
                context.runId(),
                ApprovalDecision.approved(waiting.approvalId(), "admin"));

        ActionRun completed = restartedStore.find(context.runId()).orElseThrow();
        assertThat(resumed.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(completed.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(completed.result()).isEqualTo(resumed);
        assertThat(restartedExecutor.calls()).isEqualTo(1);
        assertThat(restartedStore.findResumable("tenant-1"))
                .extracting(ActionRun::runId)
                .doesNotContain(context.runId());
    }

    @Test
    void jdbcAndInMemoryStoresRecordEquivalentFinalRuns() {
        InMemoryRunStore inMemoryStore = new InMemoryRunStore();
        JdbcRunStore jdbcStore = store();
        ActionProposal proposal = ActionProposal.user("CreateReport", Map.of("siteId", 1), "user-1");
        ActionRegistry registry = registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                ActionExecutionResult.succeeded(Map.of("reportId", 10)));

        DefaultActionRuntime inMemoryRuntime = runtime(registry, inMemoryStore);
        DefaultActionRuntime jdbcRuntime = runtime(registry, jdbcStore);
        ExecutionContext context = context("run-equivalence");

        inMemoryRuntime.handle(proposal, context);
        jdbcRuntime.handle(proposal, context);

        ActionRun inMemoryRun = inMemoryStore.find(context.runId()).orElseThrow();
        ActionRun jdbcRun = jdbcStore.find(context.runId()).orElseThrow();
        assertMeaningfulRunFields(jdbcRun, inMemoryRun);
    }

    private static JdbcRunStore store() {
        return JdbcRunStore.create(dataSource());
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        applySchema(dataSource);
        return dataSource;
    }

    private static void applySchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String schema = new String(
                    JdbcRunStoreTest.class.getClassLoader()
                            .getResourceAsStream("db/action_run/h2.sql")
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("Failed to apply H2 schema", ex);
        }
    }

    private static int rowCount(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM action_run")) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to count rows", ex);
        }
    }

    private static ActionRun.Builder fullRun(String runId) {
        return ActionRun.builder()
                .runId(runId)
                .tenantId("tenant-1")
                .userId("user-1")
                .traceId(runId + "-trace")
                .contextMetadata(Map.of("actor.roles", List.of("reviewer"), "officeId", "office-1"))
                .actionId("CreateReport")
                .proposalId(runId + "-proposal")
                .requesterId("requester-1")
                .origin(ActionOrigin.USER)
                .proposalReason("planner rationale")
                .proposalConfidence(0.9d)
                .proposalMetadata(Map.of("model", "x"))
                .input(Map.of("siteId", 1, "title", "Inspection", "nested", Map.of("ok", true)))
                .duplicateKey(runId + "-dedupe")
                .status(ActionRunStatus.RUNNING)
                .currentStage("execute-action")
                .policyDecisionType(PolicyDecisionType.ALLOW)
                .policyReason("allowed")
                .approvalId("approval-1")
                .dueAt(Instant.parse("2026-01-01T00:01:00Z"))
                .attemptToken("attempt-1")
                .result(ActionExecutionResult.succeeded(Map.of("reportId", 10)))
                .failureReason("")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:05Z"));
    }

    private static DefaultActionRuntime runtime(ActionRegistry registry, io.github.flowerjvm.flower.action.runtime.run.RunStore runStore) {
        return new DefaultActionRuntime(
                registry,
                null,
                new DefaultPolicyGate(),
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null,
                runStore);
    }

    private static ExecutionContext context(String runId) {
        return new ExecutionContext("tenant-1", "user-1", runId, runId + "-trace", Map.of());
    }

    private static ActionRegistry registryOf(ActionDefinition definition, ActionExecutionResult result) {
        return new InMemoryActionRegistry(List.of(new StubExecutor(definition, result)));
    }

    private static ActionDefinition writeAction(String actionId, Set<ActionOrigin> allowedOrigins) {
        return new ActionDefinition(actionId, actionId, "", ActionEffect.WRITE, ActionRiskLevel.MEDIUM,
                allowedOrigins, Set.of(), false, false, true, "", "", Map.of());
    }

    private static void assertMeaningfulRunFields(ActionRun actual, ActionRun expected) {
        assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
        assertThat(actual.userId()).isEqualTo(expected.userId());
        assertThat(actual.traceId()).isEqualTo(expected.traceId());
        assertThat(actual.contextMetadata()).isEqualTo(expected.contextMetadata());
        assertThat(actual.actionId()).isEqualTo(expected.actionId());
        assertThat(actual.proposalId()).isEqualTo(expected.proposalId());
        assertThat(actual.requesterId()).isEqualTo(expected.requesterId());
        assertThat(actual.origin()).isEqualTo(expected.origin());
        assertThat(actual.proposalReason()).isEqualTo(expected.proposalReason());
        assertThat(actual.proposalConfidence()).isEqualTo(expected.proposalConfidence());
        assertThat(actual.proposalMetadata()).isEqualTo(expected.proposalMetadata());
        assertThat(actual.input()).isEqualTo(expected.input());
        assertThat(actual.duplicateKey()).isEqualTo(expected.duplicateKey());
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.currentStage()).isEqualTo(expected.currentStage());
        assertThat(actual.policyDecisionType()).isEqualTo(expected.policyDecisionType());
        assertThat(actual.policyReason()).isEqualTo(expected.policyReason());
        assertThat(actual.approvalId()).isEqualTo(expected.approvalId());
        assertThat(actual.dueAt()).isEqualTo(expected.dueAt());
        assertThat(actual.result()).isEqualTo(expected.result());
        assertThat(actual.failureReason()).isEqualTo(expected.failureReason());
    }

    private record StubExecutor(ActionDefinition definition, ActionExecutionResult result) implements ActionExecutor {
        @Override
        public ActionExecutionResult execute(io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext context) {
            return result;
        }
    }

    private static final class CountingExecutor implements ActionExecutor {
        private final ActionDefinition definition;
        private final ActionExecutionResult result;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingExecutor(ActionDefinition definition, ActionExecutionResult result) {
            this.definition = definition;
            this.result = result;
        }

        @Override
        public ActionDefinition definition() {
            return definition;
        }

        @Override
        public ActionExecutionResult execute(io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext context) {
            calls.incrementAndGet();
            return result;
        }

        int calls() {
            return calls.get();
        }
    }
}
