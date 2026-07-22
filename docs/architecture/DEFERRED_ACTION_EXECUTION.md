# Deferred Action Execution

`flower-action-runtime` 0.2 introduced a non-blocking action lifecycle for work
that cannot safely finish inside `ActionRuntime.handle(...)`.

## Execution Modes

`SynchronousActionExecutor` is the synchronous contract:

```text
dispatch -> Completed(ActionExecutionResult)
```

`AsyncActionExecutor` is for short in-process work backed by a host-injected,
bounded executor or a genuinely asynchronous client:

```text
dispatch -> Async(CompletionStage)
runtime  -> WAITING_EXTERNAL
future   -> runtime.complete(runId, attemptToken, terminalResult)
```

`DeferredActionExecutor` is for durable jobs, message queues, remote workers,
and other operations whose result may arrive after a process restart:

```text
dispatch external command
-> Awaiting(operationId, dueAt, metadata)
-> ActionRun.WAITING_EXTERNAL
-> external callback/event
-> complete(runId, attemptToken, terminalResult)
```

In 0.3 all three modes share only `ActionExecutor.definition()` and
`dispatch(...)`. `SynchronousActionExecutor`, `AsyncActionExecutor`, and
`DeferredActionExecutor` expose only the operation appropriate to their mode;
async/deferred implementations no longer provide a fake `execute(...)` that
throws `UnsupportedOperationException`.

Use `ActionExecutionDispatcher.using(executor)` when adapting blocking
in-process work. The supplied executor should be bounded and selected by
execution character such as blocking risk, priority, or isolation. Action
executors should not create per-action thread pools.

The event-loop approval adapter follows the same rule: approval resolution is
submitted with `thenRunAsync(...)`. Its `EventWorker` therefore requires a
host-configured async executor; synchronous domain work must never run on the
event-loop tick.

## Durable Truth

The `ActionRun` in `RunStore` is the runtime source of truth. A Future, Flower
signal, Kafka event, or callback payload is only a delivery mechanism.

Deferred execution therefore requires a queryable `RunStore`. The runtime
fails before dispatch with `RUN_STORE_REQUIRED_FOR_DEFERRED_EXECUTION` when a
deferred executor is used with `RunStore.noop()`.

For restart-safe work, the external operation must also be durable. An
in-process `CompletionStage` cannot survive a JVM restart; a recovered
`WAITING_EXTERNAL` run must be reconciled against host or external-operation
state.

### Dispatch Atomicity Gap

Deferred dispatch is deliberately not advertised as exactly-once. The runtime
persists `RUNNING + attemptToken`, invokes the external dispatcher, and then
persists `WAITING_EXTERNAL + operationId`. A process may stop in this window:

```text
external system accepted work
-> process stopped before WAITING_EXTERNAL committed
-> ActionRun remains RUNNING without the accepted operation id
```

Every production deferred executor must therefore provide:

- an operation id deterministically derived from stable Run/attempt data;
- idempotent dispatch under that operation id;
- authenticated callbacks scoped to tenant, Run, attempt, and operation;
- reconciliation for old `RUNNING` and `WAITING_EXTERNAL` Runs;
- timeout and orphan-operation policy.

When database state and queue delivery must be atomic, persist a transactional
outbox entry and let an idempotent dispatcher publish it. CAS protects the Run
record; it does not close this external-delivery window.

## Attempt Tokens

Every execution attempt receives a new `attemptToken` through
`ActionExecutionContext`. Completion must present the same token:

```java
runtime.complete(runId, attemptToken, result);
```

This prevents a late result from an old, cancelled, or retried operation from
overwriting the current run. Attempt tokens are correlation controls, not an
authorization mechanism; completion endpoints still require host
authentication and tenant checks.

## Cancellation

`CompletableActionRuntime.cancel(runId, reason)` makes the Run terminal with
`CANCELLED`. Deferred and async executors may implement cooperative cancellation
hooks. The runtime does not rely on thread interruption for correctness: any
late completion is rejected because the stored Run is already terminal.

`CANCELLED` means the runtime will no longer accept normal completion for that
Run. It is not proof that an external worker, VPN task, file transfer, database
operation, or remote job physically stopped. Host APIs and user interfaces must
use the stable cancellation result code and warning output to distinguish a
confirmed domain cancellation from an unconfirmed external cancellation
request.

Separate runtime instances can observe the same waiting Run and invoke the
cooperative cancellation hook before one terminal CAS wins. Cancellation hooks
must therefore be idempotent for the Run attempt and, for deferred work, the
external operation id. The Run converges on one terminal result; CAS does not
make the external cancellation command exactly-once.

When an external cancellation hook throws, the Run is still protected from
late completion and records `ACTION_CANCELLED_EXTERNAL_CANCEL_FAILED` with
`MANUAL_REVIEW` retry guidance.

A synchronous executor already in `RUNNING` has no safe control boundary. The
runtime therefore returns `ACTION_RUN_NOT_CANCELLABLE_WHILE_RUNNING` with
`MANUAL_REVIEW` instead of recording a cancellation that may be false.

## Policy And State Revalidation

Approval never bypasses policy. Approved Runs execute this path:

```text
resolve action
-> validate input again
-> reevaluate policy
-> PreExecutionGuard
-> dispatch
```

`PreExecutionGuard` is the host seam for volatile facts such as permission,
resource version, cancellation, quota, and current domain phase. It must be
quick, side-effect free, and return a stable denial code when the approved
request is stale.

## Concurrency

All pipeline transitions increment `ActionRun.version` and use
`RunStore.compareAndSet`. The JDBC store performs an atomic update with:

```sql
WHERE run_id = ? AND version = ?
```

This prevents concurrent approval, cancellation, and completion writers from
silently overwriting one another. Compare-and-set does not by itself provide
distributed work claiming or leases; hosts that actively recover and dispatch
the same waiting work from multiple nodes still need a claim/lease strategy.

Concurrency tests use barriers and bounded waits rather than sleeps. They run
multiple in-memory writers, multiple H2 connections, and separate Runtime
instances racing `complete` against `cancel`. H2 coverage validates the JDBC
contract in the default build; database-specific lock behavior should also be
verified against PostgreSQL/MySQL in deployment integration tests.

## JDBC Upgrade

Fresh schemas are under `db/action_run/`. Existing 0.1 installations must apply
the matching script under `db/action_run/migration/` before using 0.2 code.
