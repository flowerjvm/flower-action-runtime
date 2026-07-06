# flower-agent-runtime-flow

Flower Flow backend for `flower-agent-runtime-core`.

This module turns a controlled `ActionProposal` into a small Flower `Flow`:

```text
record-proposal
-> reserve-duplicate
-> resolve-action
-> validate-input
-> evaluate-policy
-> execute-action
-> record-result
```

It is the default durable-backend direction for actions that need explicit
execution state, step inspection, waiting, cancellation, timeout, or recovery.

## Current Recovery Boundary

This module currently makes the action pipeline visible as Flower Steps, but it
does not yet provide full durable recovery.

`ActionExecutionSession` is still an in-memory Java object. A future durable
backend must split that session into:

- snapshot state: proposal, execution context, duplicate decision, policy
  decision, and result
- re-injected collaborators: registry, validator, policy gate, approval gate,
  duplicate policy, audit sink, and trace sink

That future version should use Flower durable steps and Flow persistence, and
must define idempotency/checkpoint boundaries so side-effecting action
execution is not replayed accidentally after restart.

Fatal JVM-level `Error`s are rethrown instead of converted to action results.
If a host application uses a persistent duplicate policy, it should reconcile
stale reservations on startup because fatal failures may bypass normal release
bookkeeping.

This module does not define the public action model. The public contracts live
in `flower-agent-runtime-core`.
