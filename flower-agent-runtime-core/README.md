# flower-agent-runtime-core

Pure core contracts for controlled business action execution.

This module intentionally has no dependency on Flower core, Spring, MCP,
provider SDKs, JSON libraries, or AI frameworks.

The first responsibility is the shared action boundary:

```text
ActionProposal
-> ActionRegistry
-> ActionInputValidator
-> PolicyGate
-> optional ApprovalGate
-> ActionExecutor
-> AuditSink / TraceSink
```

Flower Flow execution belongs in `flower-agent-runtime-flow`.

Feedback/control behavior belongs in a later optional module such as
`flower-agent-runtime-control` after host applications prove repeated patterns.
