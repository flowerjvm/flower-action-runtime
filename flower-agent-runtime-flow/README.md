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

This module does not define the public action model. The public contracts live
in `flower-agent-runtime-core`.

