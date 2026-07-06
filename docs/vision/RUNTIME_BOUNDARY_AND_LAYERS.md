# Runtime Boundary And Layers

This document narrows the Flower ecosystem boundary.

Flower should stay a runtime engine.

It should not become an AI brain, a prompt framework, a memory framework, a RAG
framework, or a general agent graph platform.

## Runtime Meaning

Runtime means the time and environment in which a program or work unit actually
executes.

For example, the JVM is not the business logic itself. It provides the execution
environment: object creation, memory management, threads, exception handling,
class loading, and runtime services.

Flower should be understood in the same spirit:

```text
upper-level intent / command
-> Flower runtime
-> lower-level executor / equipment / agent / tool
```

Flower receives executable work, manages its lifecycle, waits for events,
handles timeout, retry, cancellation, completion, failure, recovery, and reports
the result back upward.

## Core Identity

Flower is not designed to create intelligence.

Flower is designed to make execution explicit, recoverable, inspectable, and
safe to operate.

The core question is not:

```text
How smart is the worker?
```

The core question is:

```text
What executable work exists, where is it in its lifecycle, and what is allowed
to happen next?
```

AI does not change that identity.

AI may propose, plan, summarize, or execute. Flower manages the moment where a
proposal or command becomes executable work.

## TOS Analogy

A TOS may know that a container should move:

```text
Move container A to Block B / Bay 10 / Row 03 / Tier 2.
```

But the TOS should not directly control the motor-level behavior of every RTG,
ARMG, QC, or YT.

A healthier shape is:

```text
TOS
  -> work instruction
Execution runtime
  -> task lifecycle
Equipment execution module
  -> equipment-specific behavior
Physical equipment
```

In this shape, Flower sits in the execution-runtime position:

```text
TOS
-> Flower runtime
-> equipment execution module
-> physical equipment
```

Flower can turn the TOS instruction into an execution task:

```text
CREATED
WAITING
READY
RUNNING
WAITING_SIGNAL
COMPLETED
FAILED
CANCELLED
TIMED_OUT
```

The lower equipment module reports events:

```text
equipment ready
movement started
target reached
container detected
work completed
fault occurred
user cancelled
```

Flower uses those events to advance state:

```text
WAITING -> RUNNING
RUNNING -> COMPLETED
RUNNING -> FAILED
WAITING -> CANCELLED
RUNNING -> TIMED_OUT
```

Then Flower reports the task result back to the upper system.

The important boundary:

```text
The upper system says what work should happen.
Flower manages the execution lifecycle.
The lower executor knows how the work is physically or technically performed.
```

## AI Mapping

The AI version has the same shape:

```text
user / Slack / business system
-> flower-agent-runtime
-> AI runner / tool runner / domain executor
-> DB / GitHub / Jira / file system / internal API
```

TOS mapping:

```text
TOS world                 AI world
-----------------------   --------------------------------
TOS                       user / Slack / business system
work instruction          ActionRequest
Flower runtime            flower-agent-runtime
execution task            AgentTask / ActionExecution
equipment module          HermesRunner / CodexRunner / ClaudeRunner
physical equipment        DB / GitHub / Jira / files / APIs
equipment status event    tool result / progress event / agent result
work completion report    UI update / Slack reply / callback
```

The AI runner may reason internally:

```text
Which tool should I use?
Do I need more context?
Should I ask the user?
How should I summarize the result?
```

Flower should not own that reasoning loop.

Flower should own the execution envelope:

```text
What task was requested?
Who requested it?
Which tools are allowed?
What timeout applies?
Does this require approval?
Can it be cancelled?
What events were emitted?
What result schema is required?
What audit trail is recorded?
```

## Proposal Source Vs Execution Runner

AI can appear in two different places.

As a proposal source:

```text
AI suggests: run DOCUMENT_REVIEW on report-10.
```

As an execution runner:

```text
AI performs an approved AgentTask.
```

Both are valid, but neither gives AI authority.

The proposal still passes through:

```text
ActionRegistry
PolicyGate
ApprovalGate
Idempotency / duplicate policy
Audit
ResultGuard
```

The execution runner still receives bounded work:

```text
allowed tools
forbidden actions
timeout
budget
output schema
progress reporting rules
approval escalation rules
```

## flower-ai-harness Boundary

`flower-ai-harness` and `flower-agent-runtime` should not blur together.

```text
flower-ai-harness
  = reliable lifecycle for one AI task
  = prompt, model call, schema validation, retry/refine, fallback, usage trace

flower-agent-runtime
  = controlled business action runtime
  = registry, policy, approval, audit, idempotency, result guard, action lifecycle
```

Example:

```text
Document QA harness:
  asks the model to inspect a document and return structured findings.

Agent runtime:
  decides whether this user may run document QA,
  whether generated edits require approval,
  whether a duplicate request is already running,
  how the action is audited,
  and how the result becomes business state.
```

The harness makes an AI operation reliable.

The runtime decides whether and how an AI-related business action may execute.

## Control Layer Placement

Feedback/control concepts should not be placed in `flower-core`.

They should also not make `flower-ai-harness` heavy.

The clean placement is:

```text
flower-agent-runtime-control
```

This is optional and should be extracted only after host applications prove the
same control pattern repeatedly.

Layer responsibility:

```text
flower-core
  = general execution runtime:
    Flow, Step, State, Signal, Timeout, Retry, Recovery

flower-ai-harness
  = one AI task execution:
    model call, schema validation, retry/refine, fallback

flower-agent-runtime
  = business action execution envelope:
    ActionDefinition, PolicyGate, Approval, Audit, Idempotency, ResultGuard

flower-agent-runtime-control
  = optional feedback/control layer:
    sensors, error signals, correction decisions, error aggregation,
    divergence guards, circuit breakers, control events
```

P/I/D-inspired ideas should be translated into practical runtime mechanisms:

```text
P-like immediate correction
  -> severity-based retry/refine/fail-closed policy
  -> may wrap harness execution, but should not make harness own governance

I-like accumulation
  -> repeated error aggregation
  -> persistent correction candidate
  -> requires runtime state and host persistence

D-like trend detection
  -> divergence guard / circuit breaker
  -> belongs to runtime governance, not provider-specific model calls
```

The first implementation should be inside a host validation project such as
ArchDox or Agent-native-TOS. Extract `flower-agent-runtime-control` only after a
small loop works repeatedly.

## What Flower Should Own

Flower ecosystem modules may own:

```text
work creation
execution state
waiting state
external signal reception
approval waiting
cancellation
timeout
retry
recovery
result verification
audit events
upward reporting
```

Flower ecosystem modules should not own:

```text
Hermes memory internals
Codex reasoning loop internals
Claude Code context strategy
RAG retrieval strategy
vector DB chunking strategy
provider-specific prompt optimization framework
AI self-evolution loop
arbitrary agent graph authoring as the public runtime model
```

Those systems can be connected through runners, adapters, or gateways. They
should not become Flower's core identity.

## Final Boundary

Flower is the execution lifecycle layer between upper-level intent and
lower-level executors.

For AI systems:

```text
Flower is not the AI brain.
Flower is the runtime that makes AI-powered work executable, bounded,
recoverable, auditable, and safe to operate.
```

