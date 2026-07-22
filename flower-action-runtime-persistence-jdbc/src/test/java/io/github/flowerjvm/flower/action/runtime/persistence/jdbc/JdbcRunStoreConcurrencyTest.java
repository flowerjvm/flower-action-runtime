package io.github.flowerjvm.flower.action.runtime.persistence.jdbc;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRunStoreConcurrencyTest {
    private static final String ATTEMPT_TOKEN = "attempt-concurrent";
    private static final String OPERATION_ID = "operation-concurrent";

    @Test
    void compareAndSetAllowsExactlyOneConcurrentConnectionWriter() throws Exception {
        DataSource dataSource = dataSource();
        JdbcRunStore seedStore = JdbcRunStore.create(dataSource);
        ActionRun original = ActionRun.requested(
                userProposal(Map.of()),
                context("run-jdbc-cas-race"));
        seedStore.create(original);

        List<Callable<Boolean>> writers = new ArrayList<>();
        for (int writer = 0; writer < 16; writer++) {
            String writerId = "writer-" + writer;
            ActionRun candidate = original.toBuilder()
                    .version(1L)
                    .status(ActionRunStatus.RUNNING)
                    .failureReason(writerId)
                    .build();
            writers.add(() -> JdbcRunStore.create(dataSource).compareAndSet(original, candidate));
        }

        List<Boolean> results = runConcurrently(writers);

        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1L);
        ActionRun stored = seedStore.find(original.runId()).orElseThrow();
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(stored.status()).isEqualTo(ActionRunStatus.RUNNING);
        assertThat(stored.failureReason()).startsWith("writer-");
    }

    @Test
    void concurrentCompleteAndCancelConvergeOnOneTerminalResult() throws Exception {
        DataSource dataSource = dataSource();
        JdbcRunStore seedStore = JdbcRunStore.create(dataSource);
        ActionRun waiting = waitingRun("run-complete-cancel-race");
        seedStore.create(waiting);

        CyclicBarrier afterFirstFind = new CyclicBarrier(2);
        DefaultActionRuntime completingRuntime = runtime(
                new FirstFindBarrierRunStore(JdbcRunStore.create(dataSource), afterFirstFind),
                List.of());
        DefaultActionRuntime cancellingRuntime = runtime(
                new FirstFindBarrierRunStore(JdbcRunStore.create(dataSource), afterFirstFind),
                List.of());
        ActionExecutionResult completion = ActionExecutionResult.succeeded(Map.of("winner", "complete"));

        List<ActionExecutionResult> results = runConcurrently(List.of(
                () -> completingRuntime.complete(waiting.runId(), ATTEMPT_TOKEN, completion),
                () -> cancellingRuntime.cancel(waiting.runId(), "operator cancelled")));

        ActionRun stored = seedStore.find(waiting.runId()).orElseThrow();
        assertThat(stored.status()).isIn(ActionRunStatus.SUCCEEDED, ActionRunStatus.CANCELLED);
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(stored.result()).isNotNull();
        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(stored.result()));
    }

    @Test
    void concurrentCancellationUsesIdempotentExternalHookAndCommitsOnce() throws Exception {
        DataSource dataSource = dataSource();
        JdbcRunStore seedStore = JdbcRunStore.create(dataSource);
        ActionRun waiting = waitingRun("run-cancel-race");
        seedStore.create(waiting);
        IdempotentCancellationExecutor actionExecutor = new IdempotentCancellationExecutor();

        CyclicBarrier afterFirstFind = new CyclicBarrier(2);
        DefaultActionRuntime firstRuntime = runtime(
                new FirstFindBarrierRunStore(JdbcRunStore.create(dataSource), afterFirstFind),
                List.of(actionExecutor));
        DefaultActionRuntime secondRuntime = runtime(
                new FirstFindBarrierRunStore(JdbcRunStore.create(dataSource), afterFirstFind),
                List.of(actionExecutor));

        List<ActionExecutionResult> results = runConcurrently(List.of(
                () -> firstRuntime.cancel(waiting.runId(), "operator cancelled"),
                () -> secondRuntime.cancel(waiting.runId(), "operator cancelled")));

        ActionRun stored = seedStore.find(waiting.runId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ActionRunStatus.CANCELLED);
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(stored.result()));
        assertThat(actionExecutor.cancelAttempts()).isEqualTo(2);
        assertThat(actionExecutor.effectiveCancellations()).isEqualTo(1);
    }

    private static DefaultActionRuntime runtime(RunStore runStore, List<? extends ActionExecutor> executors) {
        return new DefaultActionRuntime(
                new InMemoryActionRegistry(executors),
                null,
                PolicyGate.allowAll(),
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null,
                runStore);
    }

    private static ActionRun waitingRun(String runId) {
        ActionExecutionResult accepted = ActionExecutionResult.accepted(
                "ACTION_DEFERRED",
                "Action was dispatched and is awaiting completion.",
                Map.of("runId", runId, "operationId", OPERATION_ID));
        return ActionRun.requested(
                        userProposal(Map.of("target", "cache")),
                        context(runId))
                .toBuilder()
                .status(ActionRunStatus.WAITING_EXTERNAL)
                .currentStage("execute-action")
                .attemptToken(ATTEMPT_TOKEN)
                .externalOperationId(OPERATION_ID)
                .externalOperationMetadata(Map.of("queue", "maintenance"))
                .result(accepted)
                .build();
    }

    private static ExecutionContext context(String runId) {
        return new ExecutionContext("tenant-1", "user-1", runId, runId + "-trace", Map.of());
    }

    private static ActionProposal userProposal(Map<String, Object> input) {
        return ActionProposal.userFrom(
                ActionRequestChannel.COMMAND,
                "maintenance.run",
                input,
                "user-1");
    }

    private static <T> List<T> runConcurrently(List<? extends Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        applySchema(dataSource);
        return dataSource;
    }

    private static void applySchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String schema = new String(
                    JdbcRunStoreConcurrencyTest.class.getClassLoader()
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

    private static final class FirstFindBarrierRunStore implements RunStore {
        private final RunStore delegate;
        private final CyclicBarrier barrier;
        private final AtomicBoolean firstFind = new AtomicBoolean(true);

        private FirstFindBarrierRunStore(RunStore delegate, CyclicBarrier barrier) {
            this.delegate = delegate;
            this.barrier = barrier;
        }

        @Override
        public ActionRun create(ActionRun run) {
            return delegate.create(run);
        }

        @Override
        public Optional<ActionRun> find(String runId) {
            Optional<ActionRun> found = delegate.find(runId);
            if (firstFind.compareAndSet(true, false)) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("Concurrent runtime did not reach the shared Run", exception);
                }
            }
            return found;
        }

        @Override
        public boolean compareAndSet(ActionRun expected, ActionRun updated) {
            return delegate.compareAndSet(expected, updated);
        }

        @Override
        public List<ActionRun> findResumable(String tenantId) {
            return delegate.findResumable(tenantId);
        }

        @Override
        public boolean supportsResumableRuns() {
            return delegate.supportsResumableRuns();
        }
    }

    private static final class IdempotentCancellationExecutor implements DeferredActionExecutor {
        private final CyclicBarrier bothHooksEntered = new CyclicBarrier(2);
        private final Set<String> cancelledOperations = ConcurrentHashMap.newKeySet();
        private final AtomicInteger cancelAttempts = new AtomicInteger();
        private final AtomicInteger effectiveCancellations = new AtomicInteger();

        @Override
        public ActionDefinition definition() {
            return new ActionDefinition(
                    "maintenance.run",
                    "Run maintenance",
                    "",
                    ActionEffect.WRITE,
                    ActionRiskLevel.MEDIUM,
                    Set.of(ActionRequestChannel.COMMAND),
                    Set.of(ActionProposerType.USER),
                    Set.of(),
                    false,
                    false,
                    true,
                    "",
                    "",
                    Map.of());
        }

        @Override
        public ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context) {
            return ActionDispatch.awaiting(OPERATION_ID, Instant.parse("2026-07-22T00:00:00Z"), Map.of());
        }

        @Override
        public void cancel(ActionExecutionContext context, String operationId, String reason) {
            cancelAttempts.incrementAndGet();
            try {
                bothHooksEntered.await(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("Both cancellation attempts did not reach the external hook", exception);
            }
            if (cancelledOperations.add(operationId)) {
                effectiveCancellations.incrementAndGet();
            }
        }

        int cancelAttempts() {
            return cancelAttempts.get();
        }

        int effectiveCancellations() {
            return effectiveCancellations.get();
        }
    }
}
