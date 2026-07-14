package io.github.flowerjvm.flower.action.runtime.integration;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionOrigin;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.eventloop.EventLoopActionRuntime;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.core.event.InMemoryEventBus;
import io.github.flowerjvm.flower.core.time.ManualClock;
import io.github.flowerjvm.flower.eventloop.worker.EventWorker;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EventLoopJdbcRecoveryIntegrationTest {
    @Test
    void jdbcBackedApprovalWaitCanBeRecoveredByNewEventLoopRuntimeAndApproved() {
        DataSource dataSource = dataSource();
        ManualClock clock = new ManualClock(1_000L);
        CountingExecutor firstExecutor = updateReportExecutor();
        JdbcRunStore firstStore = JdbcRunStore.create(dataSource);
        EventLoopActionRuntime firstRuntime = runtime(firstStore, clock, firstExecutor);
        ExecutionContext context = new ExecutionContext("tenant-1", "user-1", "run-integrated-recovery",
                "trace-integrated-recovery", Map.of());

        ActionExecutionResult parked = firstRuntime.handle(
                new ActionProposal("proposal-integrated-recovery", "UpdateReport", ActionOrigin.AI_PLANNER,
                        "planner", "update report", 0.9d, Map.of("siteId", 1), null, Map.of()),
                context);
        ActionRun waiting = firstStore.find(context.runId()).orElseThrow();

        assertThat(parked.status()).isEqualTo(ActionExecutionStatus.PENDING_APPROVAL);
        assertThat(waiting.status()).isEqualTo(ActionRunStatus.WAITING_APPROVAL);
        assertThat(waiting.dueAt()).isNotNull();
        assertThat(firstExecutor.calls()).isZero();
        assertThat(firstRuntime.worker().activeCount()).isEqualTo(1);

        JdbcRunStore restartedStore = JdbcRunStore.create(dataSource);
        CountingExecutor restartedExecutor = updateReportExecutor();
        EventLoopActionRuntime restartedRuntime = runtime(restartedStore, clock, restartedExecutor);

        assertThat(restartedRuntime.recoverParked("tenant-1")).isEqualTo(1);
        assertThat(restartedRuntime.worker().activeCount()).isEqualTo(1);

        restartedRuntime.signalApproval(ApprovalDecision.approved(waiting.approvalId(), "admin"));

        ActionRun completed = restartedStore.find(context.runId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(completed.result().status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(completed.result().output()).containsEntry("updated", true);
        assertThat(restartedExecutor.calls()).isEqualTo(1);
        assertThat(restartedRuntime.worker().activeCount()).isZero();
        assertThat(restartedStore.findResumable("tenant-1"))
                .extracting(ActionRun::runId)
                .doesNotContain(context.runId());
    }

    private static EventLoopActionRuntime runtime(
            JdbcRunStore runStore,
            ManualClock clock,
            CountingExecutor executor) {
        EventWorker worker = EventWorker.builder("integration-approval-worker")
                .clock(clock)
                .eventBus(InMemoryEventBus.create())
                .build();
        return EventLoopActionRuntime.create(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                new DefaultPolicyGate(),
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null,
                runStore,
                worker,
                clock,
                300_000L);
    }

    private static CountingExecutor updateReportExecutor() {
        return new CountingExecutor(
                new ActionDefinition("UpdateReport", "UpdateReport", "", ActionEffect.WRITE, ActionRiskLevel.MEDIUM,
                        Set.of(ActionOrigin.AI_PLANNER), Set.of(), false, false, true, "", "", Map.of()),
                ActionExecutionResult.succeeded(Map.of("updated", true)));
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
                    EventLoopJdbcRecoveryIntegrationTest.class.getClassLoader()
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
        public ActionExecutionResult execute(ActionExecutionContext context) {
            calls.incrementAndGet();
            return result;
        }

        int calls() {
            return calls.get();
        }
    }
}
