package io.github.flowerjvm.flower.action.runtime;

import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStoreConcurrencyTest {
    @Test
    void inMemoryCompareAndSetAllowsExactlyOneConcurrentWriter() throws Exception {
        InMemoryRunStore store = new InMemoryRunStore();
        ActionRun original = ActionRun.requested(
                ActionProposal.user("maintenance.run", Map.of(), "user-1"),
                new ExecutionContext("tenant-1", "user-1", "run-cas", "trace-cas", Map.of()));
        store.create(original);
        int writerCount = 16;
        CyclicBarrier start = new CyclicBarrier(writerCount);
        ExecutorService executor = Executors.newFixedThreadPool(writerCount);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int writer = 0; writer < writerCount; writer++) {
                String writerId = "writer-" + writer;
                ActionRun candidate = original.toBuilder()
                        .version(1L)
                        .status(ActionRunStatus.RUNNING)
                        .failureReason(writerId)
                        .build();
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return store.compareAndSet(original, candidate);
                }));
            }

            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1L);
            ActionRun stored = store.find(original.runId()).orElseThrow();
            assertThat(stored.version()).isEqualTo(1L);
            assertThat(stored.status()).isEqualTo(ActionRunStatus.RUNNING);
            assertThat(stored.failureReason()).startsWith("writer-");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void inMemoryCreateNeverOverwritesExistingRun() {
        InMemoryRunStore store = new InMemoryRunStore();
        ActionRun original = ActionRun.requested(
                ActionProposal.user("maintenance.run", Map.of(), "user-1"),
                new ExecutionContext("tenant-1", "user-1", "run-create-once", "trace-1", Map.of()));
        ActionRun replacement = original.toBuilder()
                .status(ActionRunStatus.CANCELLED)
                .build();
        store.create(original);

        assertThatThrownBy(() -> store.create(replacement))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        assertThat(store.find(original.runId())).contains(original);
    }

    @Test
    void inMemoryCompareAndSetRejectsInvalidTransitionShape() {
        InMemoryRunStore store = new InMemoryRunStore();
        ActionRun original = ActionRun.requested(
                ActionProposal.user("maintenance.run", Map.of(), "user-1"),
                new ExecutionContext("tenant-1", "user-1", "run-invalid-cas", "trace-cas", Map.of()));
        store.create(original);

        ActionRun skippedVersion = original.toBuilder().version(2L).build();
        ActionRun differentRun = original.toBuilder().runId("other-run").version(1L).build();

        assertThatThrownBy(() -> store.compareAndSet(original, skippedVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected version + 1");
        assertThatThrownBy(() -> store.compareAndSet(original, differentRun))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run ids must match");
        assertThat(store.find(original.runId())).contains(original);
    }
}
