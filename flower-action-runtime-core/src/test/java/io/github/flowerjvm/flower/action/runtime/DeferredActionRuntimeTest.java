package io.github.flowerjvm.flower.action.runtime;

import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.action.AsyncActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeferredActionRuntimeTest {
    @Test
    void deferredActionWaitsForMatchingAttemptCompletion() {
        TransitionRecordingRunStore runStore = new TransitionRecordingRunStore();
        RecordingDeferredExecutor executor = new RecordingDeferredExecutor();
        DefaultActionRuntime runtime = runtime(executor, runStore);
        ExecutionContext context = context("run-deferred");

        ActionExecutionResult accepted = runtime.handle(
                ActionProposal.user("maintenance.run", Map.of("target", "cache"), "user-1"),
                context);
        ActionRun waiting = runStore.find(context.runId()).orElseThrow();

        assertThat(accepted.status()).isEqualTo(ActionExecutionStatus.ACCEPTED);
        assertThat(accepted.code()).isEqualTo("ACTION_DEFERRED");
        assertThat(waiting.status()).isEqualTo(ActionRunStatus.WAITING_EXTERNAL);
        assertThat(waiting.externalOperationId()).isEqualTo("operation-1");
        assertThat(waiting.externalOperationMetadata()).containsEntry("queue", "maintenance");
        assertThat(waiting.attemptToken()).isNotBlank();
        assertThat(executor.dispatchCalls()).isEqualTo(1);
        assertThat(runStore.noOpFinalizationWrites()).isZero();

        ActionExecutionResult stale = runtime.complete(
                context.runId(),
                "wrong-attempt",
                ActionExecutionResult.succeeded(Map.of()));
        assertThat(stale.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(stale.code()).isEqualTo("STALE_ACTION_ATTEMPT");
        assertThat(runStore.find(context.runId()).orElseThrow().status())
                .isEqualTo(ActionRunStatus.WAITING_EXTERNAL);

        ActionExecutionResult completed = runtime.complete(
                context.runId(),
                waiting.attemptToken(),
                ActionExecutionResult.succeeded(Map.of("maintained", true)));

        assertThat(completed.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(runStore.find(context.runId()).orElseThrow().status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(runStore.noOpFinalizationWrites()).isZero();
        assertThat(runtime.complete(context.runId(), waiting.attemptToken(), completed)).isEqualTo(completed);
    }

    @Test
    void inProcessAsyncCompletionUpdatesRunWithoutBlockingHandle() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        CompletableFuture<ActionExecutionResult> future = new CompletableFuture<>();
        RecordingAsyncExecutor executor = new RecordingAsyncExecutor(future);
        DefaultActionRuntime runtime = runtime(executor, runStore);
        ExecutionContext context = context("run-async");

        ActionExecutionResult accepted = runtime.handle(
                ActionProposal.user("maintenance.run", Map.of(), "user-1"),
                context);

        assertThat(accepted.status()).isEqualTo(ActionExecutionStatus.ACCEPTED);
        assertThat(runStore.find(context.runId()).orElseThrow().status())
                .isEqualTo(ActionRunStatus.WAITING_EXTERNAL);
        assertThat(executor.asyncCalls()).isEqualTo(1);

        future.complete(ActionExecutionResult.succeeded(Map.of("maintained", true)));

        ActionRun completed = runStore.find(context.runId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(ActionRunStatus.SUCCEEDED);
        assertThat(completed.result().output()).containsEntry("maintained", true);
    }

    @Test
    void asyncActionFailsClosedBeforeDispatchWithoutQueryableRunStore() {
        CompletableFuture<ActionExecutionResult> future = new CompletableFuture<>();
        RecordingAsyncExecutor executor = new RecordingAsyncExecutor(future);
        DefaultActionRuntime runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                PolicyGate.allowAll(),
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null);

        ActionExecutionResult result = runtime.handle(
                ActionProposal.user("maintenance.run", Map.of(), "user-1"),
                context("run-no-store"));

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(result.code()).isEqualTo("RUN_STORE_REQUIRED_FOR_DEFERRED_EXECUTION");
        assertThat(executor.asyncCalls()).isZero();
    }

    @Test
    void cancellationIsTerminalAndRejectsLateExternalCompletion() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        RecordingDeferredExecutor executor = new RecordingDeferredExecutor();
        DefaultActionRuntime runtime = runtime(executor, runStore);
        ExecutionContext context = context("run-cancel");

        runtime.handle(ActionProposal.user("maintenance.run", Map.of(), "user-1"), context);
        ActionRun waiting = runStore.find(context.runId()).orElseThrow();

        ActionExecutionResult cancelled = runtime.cancel(context.runId(), "operator cancelled");

        assertThat(cancelled.status()).isEqualTo(ActionExecutionStatus.CANCELLED);
        assertThat(cancelled.code()).isEqualTo("ACTION_CANCELLED");
        assertThat(runStore.find(context.runId()).orElseThrow().status()).isEqualTo(ActionRunStatus.CANCELLED);
        assertThat(executor.cancelCalls()).isEqualTo(1);
        assertThat(runtime.complete(
                context.runId(),
                waiting.attemptToken(),
                ActionExecutionResult.succeeded(Map.of()))).isEqualTo(cancelled);
    }

    @Test
    void runningSynchronousActionIsNotReportedCancelledWithoutAControlBoundary() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        RecordingDeferredExecutor executor = new RecordingDeferredExecutor();
        DefaultActionRuntime runtime = runtime(executor, runStore);
        ExecutionContext context = context("run-active-side-effect");
        ActionRun running = ActionRun.requested(
                ActionProposal.user("maintenance.run", Map.of(), "user-1"),
                context).toBuilder()
                .status(ActionRunStatus.RUNNING)
                .attemptToken("active-attempt")
                .build();
        runStore.create(running);

        ActionExecutionResult result = runtime.cancel(context.runId(), "operator cancelled");

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.DENIED);
        assertThat(result.code()).isEqualTo("ACTION_RUN_NOT_CANCELLABLE_WHILE_RUNNING");
        assertThat(result.retryDisposition()).isEqualTo(RetryDisposition.MANUAL_REVIEW);
        assertThat(runStore.find(context.runId()).orElseThrow().status()).isEqualTo(ActionRunStatus.RUNNING);
    }

    private static DefaultActionRuntime runtime(
            io.github.flowerjvm.flower.action.runtime.action.ActionExecutor executor,
            RunStore runStore) {
        return new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                null,
                PolicyGate.allowAll(),
                null,
                new InMemoryDuplicateActionPolicy(),
                null,
                null,
                runStore);
    }

    private static ExecutionContext context(String runId) {
        return new ExecutionContext("tenant-1", "user-1", runId, runId + "-trace", Map.of());
    }

    private static ActionDefinition definition() {
        return new ActionDefinition(
                "maintenance.run",
                "Run maintenance",
                "",
                ActionEffect.WRITE,
                ActionRiskLevel.MEDIUM,
                Set.of(ActionOrigin.USER),
                Set.of(),
                false,
                false,
                true,
                "",
                "",
                Map.of());
    }

    private static final class RecordingDeferredExecutor implements DeferredActionExecutor {
        private final AtomicInteger dispatchCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public ActionDefinition definition() {
            return DeferredActionRuntimeTest.definition();
        }

        @Override
        public ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context) {
            dispatchCalls.incrementAndGet();
            assertThat(context.attemptToken()).isNotBlank();
            return ActionDispatch.awaiting(
                    "operation-1",
                    Instant.parse("2026-07-22T00:00:00Z"),
                    Map.of("queue", "maintenance"));
        }

        @Override
        public void cancel(ActionExecutionContext context, String operationId, String reason) {
            cancelCalls.incrementAndGet();
            assertThat(operationId).isEqualTo("operation-1");
        }

        int dispatchCalls() {
            return dispatchCalls.get();
        }

        int cancelCalls() {
            return cancelCalls.get();
        }
    }

    private static final class RecordingAsyncExecutor implements AsyncActionExecutor {
        private final CompletableFuture<ActionExecutionResult> future;
        private final AtomicInteger asyncCalls = new AtomicInteger();

        private RecordingAsyncExecutor(CompletableFuture<ActionExecutionResult> future) {
            this.future = future;
        }

        @Override
        public ActionDefinition definition() {
            return DeferredActionRuntimeTest.definition();
        }

        @Override
        public java.util.concurrent.CompletionStage<ActionExecutionResult> executeAsync(
                ActionExecutionContext context) {
            asyncCalls.incrementAndGet();
            return future;
        }

        int asyncCalls() {
            return asyncCalls.get();
        }
    }

    private static final class TransitionRecordingRunStore implements RunStore {
        private final InMemoryRunStore delegate = new InMemoryRunStore();
        private final AtomicInteger noOpFinalizationWrites = new AtomicInteger();

        @Override
        public ActionRun create(ActionRun run) {
            return delegate.create(run);
        }

        @Override
        public Optional<ActionRun> find(String runId) {
            return delegate.find(runId);
        }

        @Override
        public boolean compareAndSet(ActionRun expected, ActionRun updated) {
            if (expected.status() == updated.status()
                    && (expected.status() == ActionRunStatus.WAITING_EXTERNAL || expected.status().isTerminal())
                    && Objects.equals(expected.result(), updated.result())) {
                noOpFinalizationWrites.incrementAndGet();
            }
            return delegate.compareAndSet(expected, updated);
        }

        @Override
        public List<ActionRun> findResumable(String tenantId) {
            return delegate.findResumable(tenantId);
        }

        int noOpFinalizationWrites() {
            return noOpFinalizationWrites.get();
        }
    }
}
