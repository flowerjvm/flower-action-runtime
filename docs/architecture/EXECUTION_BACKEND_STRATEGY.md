# Execution Backend Strategy

> **0.2 implementation status:** the core runtime now exposes a
> `CompletableActionRuntime` contract for approval resume, correlated deferred
> completion, and cancellation. Async futures and external commands park an
> `ActionRun` in `WAITING_EXTERNAL`; the `RunStore` is the durable truth. The
> event-loop module currently owns durable approval parking/recovery, while
> external completion may arrive through any host transport. See
> [Deferred Action Execution](DEFERRED_ACTION_EXECUTION.md). References below to
> these capabilities as "future" describe the original design sequence, not the
> current 0.2 source state.

This document defines how `flower-action-runtime` should relate to Flower,
flower-eventloop, LangGraph-style graphs, and future execution backends.

The key decision:

```text
Controlled-action semantics = engine-neutral ActionPipeline (core).
Reference backend        = DefaultActionRuntime (direct, synchronous).
Observability backend    = flower-core Flow/Step (flower-action-runtime-workflow).
Approval-wait backend    = flower-eventloop (flower-action-runtime-eventloop).
Deferred completion     = CompletableActionRuntime + durable RunStore + host transport.
Optional graph backend   = future LangGraph4j adapter.
Public runtime identity  = ActionRegistry + PolicyGate + Approval + Audit.
```

Do not make graph authoring the primary public API of `flower-action-runtime`.

An earlier version of this document framed Flower Flow as "the default durable
execution backend." That framing is refined below (see
[Backend Layering](#backend-layering-refined-model)): the controlled-action
semantics do not live in any engine, the Flower Flow backend provides *observable*
control stages rather than durable waiting, and durable waiting/approval-resume
is the future event-loop backend's job.

## Why This Document Exists

`flower-action-runtime` can easily become confused with graph-based agent
frameworks. Flower already has `Flow` and `Step`, and those look similar to
LangGraph nodes and edges.

That similarity is real.

But the product identity should be different.

LangGraph-style frameworks usually start with:

```text
Which graph, nodes, edges, state, and routing logic should solve this task?
```

`flower-action-runtime` should start with:

```text
Which registered business action is being requested?
Is this actor allowed to run it?
Does it require approval?
Which controlled execution plan should run?
How do we audit, resume, cancel, and diagnose it?
```

So the runtime may use flow/graph mechanics internally, but the public control
model should remain action-first and policy-first.

## Backend Layering (Refined Model)

This section captures what the parity work between the direct and Flow backends
actually proved. The lesson was not "stabilize the semantics on flower-core." It
was the opposite: the semantics must live outside any engine, and each engine is
a dumb driver of the same pipeline.

### Semantics live in the pipeline, not the engine

The controlled-action semantics - registry lookup, input validation, policy,
approval boundary, audit, idempotency, and failure handling - live in an
engine-neutral `ActionPipeline` in `flower-action-runtime-core`, as an ordered list
of stages over a shared `ActionExecutionSession`.

```text
record-proposal -> reserve-duplicate -> resolve-action -> validate-input
-> evaluate-policy -> execute-action -> record-result (finalize)
```

`DefaultActionRuntime` (direct, synchronous) runs these stages in-thread and is the
**reference implementation** of the envelope semantics. Every other backend must
run the *same* stages and stay in behavioral parity with the direct runtime
(status, message, output, and the full audit trail). Parity is enforced by tests.
An engine is a driver; it must not carry governance logic.

### The two backends and their real roles

`flower-action-runtime-workflow` (flower-core `Flow`/`Step`) exists for
**observability**, not durability. It renders the control stages as a Flower Flow
so `Engine.dump()`, the console, and lifecycle listeners can show which control
stage an action is at. Because the gate stages contain no `stay()`/waiting, this
backend drives the pipeline as a synchronous sequencer: it is behaviorally
identical to the direct runtime (proven by parity tests) and does **not** suspend,
wait for approval across time, or recover across restart.

```text
flower-action-runtime-workflow
  = observable control stages for a synchronous governance pass
  != durable waiting / approval-resume / crash recovery
```

Do not build human-approval, long external waits, or resume-after-restart on the
workflow backend. It cannot suspend. Its `ActionExecutionSession` is an in-memory
object that is discarded when `handle(...)` returns.

`flower-action-runtime-eventloop` is the **approval-wait** backend. It parks a
persisted `WAITING_APPROVAL` Run on a correlated signal and can rebuild those
waits from `RunStore.findResumable(...)` after restart. The signal is a delivery
mechanism; the host approval system must be able to redeliver or reconcile the
decision until the resumed Run transition commits.

Approval resolution uses `EventStepResult.thenRunAsync(...)`; it does not call
the action pipeline on the EventLoop worker tick. Hosts must configure the
`EventWorker` with a bounded, appropriately isolated `asyncExecutor` lane.

The public lifecycle contract has evolved to `CompletableActionRuntime`.
`handle(...)` may return `PENDING_APPROVAL` or `ACCEPTED`; a later `resume(...)`,
`complete(...)`, or `cancel(...)` performs a versioned Run transition. External
jobs do not require an event-loop worker to remain occupied: a host queue,
webhook, message consumer, or scheduler can call the same completion contract.
The current event-loop adapter parks approval waits, not arbitrary external-job
timers; external timeout/reconciliation remains a host responsibility.

The current `ActionRun` stores the approval id and the resulting lifecycle
transition, but not a separate durable approval-decision inbox/outbox record.
Exactly-once handoff from an external approval database therefore requires host
transactional delivery or reconciliation.

`flower-eventloop` is an MVP/experimental module whose API is not yet stable.
Coupling the durable backend to it is acceptable because both are pre-1.0 and are
meant to co-evolve, but it is a deliberate bet on an experimental engine.

### Where the module boundary sits

```text
flower-action-runtime-core       ActionPipeline + contracts (engine-neutral, the truth)
  DefaultActionRuntime          direct synchronous reference backend
flower-action-runtime-workflow   flower-core Flow/Step observability backend
flower-action-runtime-eventloop  flower-eventloop approval-wait backend
flower-action-runtime-persistence-jdbc  durable RunStore + atomic CAS
```

## Core Rule

```text
ActionRegistry is the source of truth.
PolicyGate controls execution.
ApprovalGate controls interlocks.
AuditSink and TraceSink record what happened.
ActionPipeline owns the controlled-action semantics.
DefaultActionRuntime is the synchronous reference backend.
flower-action-runtime-workflow exposes the same stages for observability.
flower-action-runtime-eventloop is the future durable-wait backend.
Optional runtime-control may wrap execution only after host applications prove
repeated sensor/error/correction patterns.
```

Dynamic routing should happen by selecting, proposing, or composing registered
actions. It should not happen by allowing model output to freely mutate graphs.

## Conceptual Layers

```text
Host adapter normalizes user / system / MCP / scheduler / planner requests
-> ActionProposal
-> ActionRegistry
-> PolicyGate
-> optional ApprovalGate
-> optional runtime-control wrapper
-> controlled execution backend
   -> DefaultActionRuntime
   -> flower-action-runtime-workflow
   -> future flower-action-runtime-eventloop
   -> future optional LangGraph4j / external workflow adapter
-> AuditSink / TraceSink
-> ActionExecutionResult
```

The selected backend is an implementation detail behind the runtime boundary.
Every backend must drive the same `ActionPipeline` semantics and obey the same
policy, approval, audit, idempotency, timeout, and cancellation contract.

## Flower Step vs LangGraph Node

Flower `Step` can be used like a LangGraph node.

Example:

```text
PrepareDocumentContextStep
-> GenerateDraftStep
-> ValidateDraftStep
-> RequestUserApprovalStep
-> ReviewDocumentStep
-> ApplyReviewResultStep
-> PersistFinalDocumentStep
-> EmitCompletedEventStep
```

This is structurally similar to a node graph.

The difference is not whether steps/nodes exist. The difference is what the
framework asks the developer to model first.

```text
LangGraph:
  Model the graph first.

flower-action-runtime:
  Model the registered business action first.
  Let the runtime select the controlled execution backend behind it.
```

## Backend Selection Model

The runtime does not start by choosing a graph engine. It starts by resolving a
registered action and running the core `ActionPipeline`.

Implemented today:

```text
DefaultActionRuntime
  = direct synchronous reference backend.

flower-action-runtime-workflow
  = same pipeline rendered as Flower Flow/Step for observability.
```

Future:

```text
flower-action-runtime-eventloop
  = durable-wait backend for approval waits, AI/tool callbacks, timeout,
    cancellation, and resume.

LangGraph4j / external workflow adapters
  = optional backends behind the same action/policy/audit boundary.
```

Example:

```text
@Action("generate-and-review-document")
-> runtime receives invocation
-> ActionRegistry resolves ActionDefinition
-> PolicyGate evaluates actor, tenant, data, risk, and effect type
-> ApprovalGate blocks if action start approval is required
-> selected backend drives the shared ActionPipeline
-> action executor performs document generation, validation, review, persist
-> AuditSink records proposal, policy decision, approval, execution, result
```

If the action itself needs a domain workflow, that domain workflow is still an
executor detail behind the controlled action boundary:

```text
PrepareDocumentContextStep
GenerateDraftStep
ValidateDraftStep
future eventloop-backed approval wait, if needed
ReviewDocumentStep
ApplyReviewResultStep
PersistFinalDocumentStep
EmitCompletedEventStep
```

The user sees a controlled action. The runtime sees an action envelope. Flower
Flow sees control stages only when the selected backend is
`flower-action-runtime-workflow`; durable waits belong to the future event-loop
backend.

## Approval During Controlled Execution

Approval can appear in two places.

### Before Action Start

Use `PolicyGate` or `ApprovalGate` before submitting the execution plan.

Examples:

```text
May this user start this action?
Does this write action require approval before any work begins?
Is the tenant allowed to use this worker?
```

### During Durable-Wait Execution

Use the future durable-wait backend when the approval point is part of the
business process and must survive time, callbacks, or restart.

Examples:

```text
AI generated a draft.
User must approve the draft before review continues.

AI proposed a legal phrase.
Reviewer must approve before it is applied to a report.
```

The approval wait must not block a worker thread while waiting for a human.

Correct behavior:

```text
RequestUserApprovalStep
-> creates ApprovalRequest
-> stores approvalId and current run state
-> emits UI/notification event
-> moves execution to WAITING_APPROVAL
-> resumes when ApprovalGranted or ApprovalRejected event arrives
```

This makes approval a visible and recoverable workflow state, not a hidden
synchronous wait.

Note: this suspend-and-resume-on-event behavior is the **durable-wait
(event-loop) backend's** role, not the synchronous workflow backend's. On the
`flower-action-runtime-workflow` backend the approval boundary short-circuits to a
`PENDING_APPROVAL` result and the run ends; it does not park a durable await. See
[Backend Layering](#backend-layering-refined-model).

## User-Facing API

The long-term simple API may look like:

```java
@WorkerProfile("document-worker")
public interface DocumentWorker {

    @Action("generate-and-review-document")
    DocumentResult generateAndReview(DocumentRequest request);
}
```

The call:

```java
documentWorker.generateAndReview(request);
```

should be converted by the Spring/proxy layer into:

```text
ActionRuntime.invoke(
  workerProfileId = "document-worker",
  actionId = "generate-and-review-document",
  input = request
)
```

The runtime then performs registry lookup, policy evaluation, approval checks,
controlled backend selection, trace, and audit.

The annotation/proxy layer is convenience. It must remain a thin adapter over
the explicit runtime contracts.

## Execution Backend SPI

Do not add multiple backends before the core `ActionPipeline` semantics are
proven.

After repeated host use proves a second execution style is needed, introduce a
backend SPI such as:

```java
public interface ControlledActionBackend {
    ActionExecutionResult execute(ActionExecution execution);
}
```

Suggested implementations:

```text
DefaultActionRuntime
  = direct synchronous reference backend.

WorkflowActionRuntime
  = Flower Flow/Step observability backend.

EventLoopActionRuntime
  = future durable-wait backend.

LangGraph4jActionExecutor
  = optional future adapter.

ExternalWorkflowActionExecutor
  = optional adapter for host systems.
```

The SPI should receive an already-approved or policy-evaluated execution request.
It should not be the place where business policy is decided.

## AI Execution Backend SPI

Separate the controlled action backend from the AI execution backend.

The controlled action backend decides how the action envelope is driven. Inside
the action executor, one or more steps may need AI execution. Those AI steps
should not be hardwired to `flower-ai-harness`.

Use an AI execution SPI such as:

```java
public interface AiExecutionBackend {
    AiExecutionResult execute(AiExecutionRequest request);
}
```

Possible implementations:

```text
FlowerAiHarnessExecutionBackend
  = wraps flower-ai-harness.

SpringAiAgentExecutionBackend
  = wraps Spring AI agent ecosystem execution, agent client, agent utils, or
    host-provided Spring AI agent flows.

DirectSpringAiChatExecutionBackend
  = uses Spring AI ChatClient directly for simpler tasks.

HostProvidedAiExecutionBackend
  = calls a host application's own AI execution service.
```

The runtime control boundary must stay the same:

```text
ActionProposal
-> ActionRegistry
-> PolicyGate
-> ApprovalGate
-> controlled execution backend
-> AiExecutionBackend
-> Sensor / Control / Trace
-> ActionExecutionResult
```

This means `flower-action-runtime` can support both `flower-ai-harness` and
Spring AI agent-style execution without making either one mandatory.

Important:

```text
AI execution backends execute model/agent work.
They do not own business policy.
They do not bypass ActionRegistry, PolicyGate, ApprovalGate, AuditSink, or
TraceSink.
```

## LangGraph4j Adapter Position

A future LangGraph4j adapter can be useful when a specific action is naturally
an AI reasoning graph.

Good use cases:

```text
tool-calling loop
multi-agent handoff inside one approved action
stateful reasoning graph
conditional AI planner routing
conversation-oriented graph
```

But LangGraph4j must remain an execution backend, not the public control model.

Correct boundary:

```text
ActionProposal
-> ActionRegistry
-> PolicyGate
-> ApprovalGate
-> LangGraph4jActionExecutor
-> graph execution
-> standard ActionExecutionResult
-> AuditSink / TraceSink
```

Incorrect boundary:

```text
User defines arbitrary LangGraph graph as the runtime center.
Graph nodes call tools directly.
Graph nodes bypass PolicyGate.
Graph nodes perform writes without ApprovalGate.
Audit is optional or graph-specific.
```

If LangGraph4j is used, every tool call or business effect still needs to be
mediated by the runtime, MCP proxy, or controlled executor boundary.

## What To Provide In v1

The first implementation should provide:

```text
ActionDefinition
ActionProposal
ActionRegistry
ActionPipeline
PolicyGate
ApprovalGate contract
AuditSink
TraceSink
DefaultActionRuntime
WorkflowActionRuntime
runtime parity tests
```

The first observable workflow mapping can be small:

```text
record-proposal
reserve-duplicate
resolve-action
validate-input
evaluate-policy
execute-action
record-result
```

This stage mapping should be boring and explicit. Do not create a generic graph
engine inside `flower-action-runtime`.

## Reusable Execution Patterns

`flower-action-runtime` should first provide contracts, parity tests, and a small
set of documented execution patterns. It should not imply that every pattern is
implemented as a Flower Flow backend.

The host application may still use Flower Flow, flower-ai-harness,
flower-eventloop, or plain domain code inside an approved action executor. The
runtime boundary remains the same.

Suggested initial patterns:

```text
DirectSynchronousAction
  = run the core ActionPipeline and executor in-thread.

ObservableControlPipeline
  = run the same ActionPipeline through flower-action-runtime-workflow so
    control stages are visible.

SingleHarnessAction
  = execute one AI harness behind the controlled action boundary.

HarnessValidationAction
  = prepare prompt/context, run harness, validate output, emit result.

GenerateValidateReviewAction
  = generate draft, validate draft, request approval if needed, review,
    persist final result.

ControlledToolAction
  = execute an allowed tool or domain service through a controlled boundary.

MultiHarnessPipelineAction
  = run two or more harnesses in a fixed business sequence.

FutureApprovalWaitAction
  = event-loop durable wait for a human approval signal.

FutureToolCallbackWaitAction
  = event-loop durable wait for an external tool or MCP callback.
```

Example document execution shape:

```text
GenerateDocumentFlow
1. PrepareDocumentContextStep
2. GenerateDraftStep
3. ValidateDraftStep
4. Future event-loop approval wait, if approval must suspend and resume
5. ReviewDocumentStep
6. ApplyReviewResultStep
7. PersistFinalDocumentStep
8. EmitCompletedEventStep
```

This looks similar to a graph, but the public model is still the registered
business action. Flower Flow, eventloop waits, or graph adapters are internal
execution choices behind the controlled action boundary.

## Reusable Step / Stage Patterns

The runtime should also document small reusable stage patterns. Some are current
`ActionPipeline` stages; some are host-domain steps; durable waits are future
event-loop patterns.

Suggested initial step categories:

```text
PrepareContextStep
  = load tenant/user/domain context and normalize action input.

BuildPromptStep
  = convert domain context into an AI harness request.

RunHarnessStep
  = call flower-ai-harness and receive a structured result.

ValidateOutputStep
  = validate schema, required fields, policy-sensitive content, and confidence.

RefineOrRetryDecisionStep
  = decide whether to retry, refine, fallback, fail, or continue.

RequestApprovalStep
  = future event-loop pattern: create ApprovalRequest, emit event, park run.

ResumeAfterApprovalStep
  = future event-loop pattern: continue, branch, or cancel based on approval.

RunControlledToolStep
  = call a domain tool or MCP proxy through the controlled boundary.

PersistResultStep
  = store accepted result in the host application.

EmitEventStep
  = publish domain/runtime event for UI, audit, or downstream workflow.

FailActionStep
  = convert unrecoverable failure into a controlled failed action result.
```

These steps should be composable. Host applications must be able to write their
own domain-specific steps freely.

Important rule:

```text
Custom steps are allowed.
Runtime control boundaries are still mandatory.
```

For example, a domain-specific step may generate an ArchDox report paragraph,
but it should not bypass action policy, approval, audit, or harness/tool
boundaries.

## Host Execution Customization

The framework should not force every action into a predefined workflow template.

Recommended model:

```text
framework provides the controlled action boundary
host application provides domain-specific executors, Steps, or workflows
ActionDefinition maps actionId to the approved executor
the selected backend drives the shared ActionPipeline before execution
```

This allows both simple and advanced usage:

```text
simple:
  @Action("run-document-qa")
  -> SingleHarnessAction executor

advanced:
  @Action("generate-and-review-document")
  -> custom domain workflow with domain-specific Steps
```

The public extension point should be "provide a controlled Flow/Step execution
plan for this registered action", not "let the AI freely build arbitrary graph
nodes at runtime".

## What Not To Do In v1

Avoid:

```text
public AgentGraph API as the main interface
graph mutation by model output
LangGraph4j as a required dependency
policy logic inside graph nodes
approval handled only as a UI callback outside the workflow state
tool calls that bypass ActionRegistry or MCP proxy boundaries
unbounded planner loops
direct child flow ticking from inside another flow
```

## Phase Recommendation

```text
Phase 1:
  Implement explicit action registry, policy gate, audit, and Flower executor.

Phase 2:
  Validate with ArchDox document actions and approval states.

Phase 3:
  Add annotation/proxy convenience once repeated action shapes are proven.

Phase 4:
  Add ActionExecutionBackend SPI if multiple execution styles are really needed.

Phase 5:
  Add optional LangGraph4j adapter only for actions that benefit from graph
  reasoning, while preserving runtime policy/audit boundaries.
```

## Prompt Rule For Future AI Implementers

When asking an AI coding agent to implement this project, include this rule:

```text
Do not make LangGraph-style graphs the main public API.
Keep ActionRegistry, PolicyGate, ApprovalGate, AuditSink, and TraceSink as the
runtime source of truth. Keep controlled-action semantics in the engine-neutral
ActionPipeline. Use the workflow backend only to make control stages observable;
use a future event-loop backend for durable waits. LangGraph4j, if added later,
is only an adapter behind the same controlled action boundary.
```

This prevents the framework from turning into a graph clone and keeps the
identity clear:

```text
Simple action declaration outside.
Controlled execution runtime inside.
ActionPipeline as the semantic source of truth.
Workflow backend for observability.
Future event-loop backend for durable waits.
Optional graph backends only behind the runtime contract.
```
