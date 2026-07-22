# flower-action-runtime-core

Pure core contracts for controlled business action execution.

This module intentionally has no dependency on Flower core, Spring, MCP,
provider SDKs, JSON libraries, or AI frameworks.

The first responsibility is the shared action boundary:

```text
ActionProposal
-> ActionRegistry
-> ActionInputValidator
-> PolicyGate
-> DuplicateActionPolicy
-> optional ApprovalGate
-> PreExecutionGuard
-> ActionExecutor
-> AuditSink / TraceSink
```

These stages live in an engine-neutral `ActionPipeline` over a shared
`ActionExecutionSession`. `DefaultActionRuntime` (direct, synchronous) runs them
in-thread for synchronous actions and is the **reference implementation** of the envelope semantics
(policy, approval, audit, idempotency, failure handling). Any execution backend
must run the same stages and stay in behavioral parity with the direct runtime;
an engine is only a driver and must not carry governance logic.

Flower Flow execution belongs in `flower-action-runtime-workflow`, which drives
these same stages to make them observable. The event-loop backend parks and
recovers approval waits; the RunStore remains the durable source of truth. See
`../docs/architecture/EXECUTION_BACKEND_STRATEGY.md` (Backend Layering).

Synchronous actions use `SynchronousActionExecutor`. Long-running actions use
`AsyncActionExecutor` or `DeferredActionExecutor`; the three execution modes do
not inherit unusable mode-specific methods from one another. Async/deferred actions
transition to `WAITING_EXTERNAL`. `CompletableActionRuntime` records later
completion or cancellation using the Run's attempt token. See
`../docs/architecture/DEFERRED_ACTION_EXECUTION.md`.

Duplicate reservation runs only after action resolution, validation, and policy
allow the current request. This prevents an unauthorized request from receiving
a cached result through `RETURN_EXISTING`. A production duplicate policy must
still scope keys by at least tenant, action id, and idempotency key, and add
principal or resource scope when authorized callers cannot safely share results.

Feedback/control behavior belongs in a later optional module such as
`flower-action-runtime-control` after host applications prove repeated patterns.
