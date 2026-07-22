# 0.2.x Migration And Module Impact

This note explains how the `0.2.0` release affects the modules in this
repository and applications moving from the Maven Central `0.1.0` release.

## 0.2.1 Safety Refinements

The `0.2.1` line keeps the 0.2 executor and RunStore shapes while tightening
host-facing defaults:

- `ActionProposal.builder(...)` and `toBuilder()` reduce canonical-constructor
  noise at host adapter boundaries;
- unknown `FAILED` results now default to `MANUAL_REVIEW`, not
  `AFTER_BACKOFF`;
- explicit `retryableFailure`, `correctableFailure`, `permanentFailure`, and
  `manualReviewFailure` factories make retry intent visible;
- context-aware duplicate `complete` and `release` overloads let policies keep
  the same trusted tenant scope used by `reserve`;
- `InMemoryDuplicateActionPolicy` scopes keys by
  `tenantId + actionId + idempotencyKey`.

Hosts that intentionally relied on the old implicit `AFTER_BACKOFF` failure
default must switch to `retryableFailure(...)`. Existing duplicate policy
implementations remain source-compatible because the context-aware overloads
delegate to the 0.2.0 methods by default, but multi-tenant implementations
should override the new overloads.

## Compatibility Summary

`0.2` is intentionally not binary compatible with `0.1`. Recompile every
consumer against the same `0.2` module set.

Source compatibility is preserved for the common construction paths:

- the 0.1 nine-argument `ActionProposal` constructor remains available;
- the 0.1 three-argument `ActionExecutionResult` constructor remains available;
- synchronous `ActionExecutor.execute(...)` implementations still work.

Source changes are still required when a consumer:

- exhaustively switches over `ActionExecutionStatus`, because `ACCEPTED` and
  `CANCELLED` are new;
- invokes a record's canonical constructor or depends on its component shape;
- implements `RunStore`, because every store must now provide an atomic
  `compareAndSet(...)` implementation and the unconditional `update(...)`
  method has been removed;
- uses the EventLoop approval backend without an async executor lane;
- reads or writes the JDBC `action_run` table directly.

## Module Impact

| Module | 0.2 impact | Required action |
| --- | --- | --- |
| `flower-action-runtime-core` | Adds policy revalidation, `PreExecutionGuard`, stable result codes, async/deferred dispatch, cancellation, identity split, and versioned Run transitions. | Recompile; handle the new statuses and use `code`/`retryDisposition` for machine decisions. |
| `flower-action-runtime-workflow` | Implements `CompletableActionRuntime` and drives the same updated pipeline. Deferred dispatch returns `ACCEPTED` without blocking a Flow tick. | Keep the same `RunStore` available for later `complete`/`cancel`. |
| `flower-action-runtime-persistence-jdbc` | Persists Run version, request channel, proposer type, external-operation correlation, result code, and retry disposition. CAS is atomic in SQL. | Apply the matching `db/action_run/migration/*-0.1-to-0.2.sql` before starting 0.2 code. |
| `flower-action-runtime-eventloop` | Approval resolution executes with `EventStepResult.thenRunAsync(...)` instead of on the EventWorker tick. | Configure `EventWorker.asyncExecutor(...)` with a bounded, isolated lane. |
| `flower-action-runtime-integration-test` | Verifies JDBC-backed approval recovery across runtime instances. | Keep this module in full reactor verification; it is not a published artifact. |

## Outside This Reactor

The neighboring `flower-action-runtime-samples` repository is not an internal
module and may still resolve the Maven Central `0.1.0` artifacts. Upgrade it as
a separate consumer to `0.2.0`. Its two custom
in-memory `RunStore` implementations must also change `create` from overwrite
semantics to fail-on-duplicate semantics and should implement explicit CAS for
clear concurrent behavior.

## Custom RunStore Contract

Every custom `RunStore` must implement `compareAndSet(expected, updated)` for
its storage scope. In-memory implementations can use an atomic map operation;
durable stores need a storage-level atomic condition. There is no default CAS
fallback and no unconditional update operation in the runtime SPI.

All implementations must also follow these rules:

1. `create` fails instead of overwriting an existing `runId`.
2. CAS accepts only the expected version and writes `expected.version + 1`.
3. `supportsResumableRuns()` returns `false` when a later call cannot query the
   Run. Async/deferred dispatch then fails before external work begins.
4. `findResumable` includes non-terminal Runs, including
   `WAITING_APPROVAL`, `RUNNING`, and `WAITING_EXTERNAL`.

CAS prevents stale writers from overwriting a newer Run. It does not implement
distributed work claiming, leases, or leader election.

## Result And Lifecycle Handling

Treat the result status and code as separate dimensions:

```text
PENDING_APPROVAL  approval interlock; resume with an ApprovalDecision
ACCEPTED          dispatch succeeded; observe/complete the persisted Run later
SUCCEEDED         terminal success
CANCELLED         terminal cancellation
DENIED            policy, approval, or control denial
VALIDATION_FAILED caller/input correction required
FAILED            execution/runtime failure
```

Do not parse `message` to decide retry behavior. Use `code` and
`RetryDisposition`.

An unclassified failure is not safely retryable. Use an explicit failure
factory whenever the executor knows whether the condition is transient,
correctable, permanent, or operationally ambiguous.

## Deferred Completion And Cancellation

`AsyncActionExecutor` is for bounded in-process asynchronous work.
`DeferredActionExecutor` is for durable queues, remote workers, and callbacks.
Both require a queryable `RunStore` and use the Run's `attemptToken` to reject
stale completion:

```java
runtime.complete(runId, attemptToken, terminalResult);
runtime.cancel(runId, reason);
```

Cancellation is cooperative. A synchronous action already in `RUNNING` cannot
be truthfully cancelled and returns
`ACTION_RUN_NOT_CANCELLABLE_WHILE_RUNNING` with `MANUAL_REVIEW`.
Cancellation hooks must be idempotent because separate Runtime instances may
invoke the same external cancellation concurrently before one terminal CAS
wins.

Deferred dispatch does not make external delivery exactly-once. The process
can stop after a queue or remote system accepts work but before the operation
id is committed to `WAITING_EXTERNAL`. Use deterministic operation ids,
idempotent dispatch, authenticated callbacks, reconciliation, and a
transactional outbox when atomic database-to-queue delivery is required.

`CANCELLED` is the terminal runtime decision to stop accepting normal
completion. It is not proof that an external operation physically stopped.
Surface `ACTION_CANCELLED_EXTERNAL_CANCEL_FAILED` and its `MANUAL_REVIEW`
guidance distinctly in host APIs and operator interfaces.

## Verification

Run the whole reactor from a clean state after changing the core contract:

```powershell
mvn clean verify
```

The 0.2 reactor verifies core, workflow parity, JDBC persistence, EventLoop
behavior, and JDBC/EventLoop recovery together.
