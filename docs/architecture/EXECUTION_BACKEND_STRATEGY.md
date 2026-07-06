# Execution Backend Strategy

This document defines how `flower-agent-runtime` should relate to Flower as the
default durable backend, LangGraph-style graphs, and future execution backends.

The key decision:

```text
Default durable execution backend = Flower.
Future optional backend = LangGraph4j adapter or other executor adapter.
Public runtime identity = ActionRegistry + PolicyGate + Approval + Audit.
```

Do not make graph authoring the primary public API of `flower-agent-runtime`.

## Why This Document Exists

`flower-agent-runtime` can easily become confused with graph-based agent
frameworks. Flower already has `Flow` and `Step`, and those look similar to
LangGraph nodes and edges.

That similarity is real.

But the product identity should be different.

LangGraph-style frameworks usually start with:

```text
Which graph, nodes, edges, state, and routing logic should solve this task?
```

`flower-agent-runtime` should start with:

```text
Which registered business action is being requested?
Is this actor allowed to run it?
Does it require approval?
Which controlled execution plan should run?
How do we audit, resume, cancel, and diagnose it?
```

So the runtime may use flow/graph mechanics internally, but the public control
model should remain action-first and policy-first.

## Core Rule

```text
ActionRegistry is the source of truth.
PolicyGate controls execution.
ApprovalGate controls interlocks.
AuditSink and TraceSink record what happened.
Flower is the default backend for durable, high-risk, or long-running action
execution.
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
-> AgentActionExecutor
   -> default: FlowerActionExecutor
   -> future optional: LangGraph4jActionExecutor
   -> future optional: ExternalWorkflowActionExecutor
-> AuditSink / TraceSink
-> ActionExecutionResult
```

The selected executor is an implementation detail behind the runtime boundary.
Every executor must obey the same policy, approval, audit, idempotency, timeout,
and cancellation contract.

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

flower-agent-runtime:
  Model the registered business action first.
  Let the runtime select or submit the controlled Flower Flow behind it.
```

## Default Flower Backend Model

For v1, durable/high-risk/long-running execution should be Flower-based.
Simple low-risk actions may use a direct executor behind the same policy and
audit boundary.

Example:

```text
@Action("generate-and-review-document")
-> runtime receives invocation
-> ActionRegistry resolves ActionDefinition
-> PolicyGate evaluates actor, tenant, data, risk, and effect type
-> ApprovalGate blocks if action start approval is required
-> FlowerActionExecutor submits GenerateAndReviewDocumentFlow
-> Flow runs document generation, validation, mid-flow approval, review, persist
-> AuditSink records proposal, policy decision, approval, execution, result
```

Inside the Flower Flow:

```text
PrepareDocumentContextStep
GenerateDraftStep
ValidateDraftStep
RequestUserApprovalStep
ReviewDocumentStep
ApplyReviewResultStep
PersistFinalDocumentStep
EmitCompletedEventStep
```

The user sees a controlled action. The runtime sees an action envelope. Flower
sees a flow and steps only when the selected backend is Flower.

## Approval Inside A Flow

Approval can appear in two places.

### Before Action Start

Use `PolicyGate` or `ApprovalGate` before submitting the execution plan.

Examples:

```text
May this user start this action?
Does this write action require approval before any work begins?
Is the tenant allowed to use this worker?
```

### During Flow Execution

Use a Flower step when the approval point is part of the business process.

Examples:

```text
AI generated a draft.
User must approve the draft before review continues.

AI proposed a legal phrase.
Reviewer must approve before it is applied to a report.
```

The approval step must not block a worker thread while waiting for a human.

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

## Executor SPI

Do not add multiple backends before the Flower backend is proven.

After the v1 Flower executor is stable, introduce an executor SPI such as:

```java
public interface AgentActionExecutor {
    ActionExecutionResult execute(ActionExecution execution);
}
```

Suggested implementations:

```text
FlowerActionExecutor
  = default official backend.

LangGraph4jActionExecutor
  = optional future adapter.

ExternalWorkflowActionExecutor
  = optional adapter for host systems.
```

The SPI should receive an already-approved execution request. It should not be
the place where business policy is decided.

## AI Execution Backend SPI

Separate the action/workflow executor from the AI execution backend.

`FlowerActionExecutor` decides how a controlled action is executed as a Flower
workflow. Inside that workflow, one or more steps may need AI execution. Those
AI steps should not be hardwired to `flower-ai-harness`.

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
-> Flower workflow
-> AiExecutionBackend
-> Sensor / Control / Trace
-> ActionExecutionResult
```

This means `flower-agent-runtime` can support both `flower-ai-harness` and
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
PolicyGate
ApprovalGate contract
AuditSink
TraceSink
AgentActionExecutor contract
FlowerActionExecutor
simple Flow template mapping
```

The first Flow templates can be small:

```text
SingleHarnessActionFlow
ApprovalRequiredActionFlow
GenerateValidateReviewFlow
ToolExecutionActionFlow
```

These templates should be boring and explicit. Do not create a generic graph
engine inside `flower-agent-runtime`.

## Reusable Flow Patterns

`flower-agent-runtime` should provide a small set of reusable Flower Flow
patterns before considering external graph engines.

These are not meant to cover every domain. They are starting points that host
applications can copy, extend, or map to their own domain flows.

Suggested initial patterns:

```text
SingleActionFlow
  = execute one approved business action and emit result/audit.

SingleHarnessActionFlow
  = execute one AI harness as a controlled action.

HarnessValidationFlow
  = prepare prompt/context, run harness, validate output, emit result.

ApprovalRequiredActionFlow
  = run pre-approval or mid-flow approval before continuing.

GenerateValidateReviewFlow
  = generate draft, validate draft, request approval if needed, review,
    persist final result.

ToolExecutionActionFlow
  = execute an allowed tool or domain service through a controlled boundary.

MultiHarnessPipelineFlow
  = run two or more harnesses in a fixed business sequence.
```

Example document flow:

```text
GenerateDocumentFlow
1. PrepareDocumentContextStep
2. GenerateDraftStep
3. ValidateDraftStep
4. RequestUserApprovalStep
5. ReviewDocumentStep
6. ApplyReviewResultStep
7. PersistFinalDocumentStep
8. EmitCompletedEventStep
```

This looks similar to a graph, but it is still a Flower workflow. The point is
not to hide `Flow` and `Step`. The point is to keep them inside a controlled
action execution model.

## Reusable Step Patterns

The runtime should also document and eventually provide small reusable step
patterns.

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
  = create ApprovalRequest, emit event, move run to WAITING_APPROVAL.

ResumeAfterApprovalStep
  = continue, branch, or cancel based on approval result.

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

## Custom Step Support

The framework should not force every workflow into a predefined template.

Recommended model:

```text
framework provides common Flow/Step patterns
host application provides domain-specific Steps
ActionDefinition maps actionId to a Flow template or Flow factory
runtime backend submits the selected Flower Flow only when Flower execution is
appropriate after policy/approval checks
```

This allows both simple and advanced usage:

```text
simple:
  @Action("run-document-qa")
  -> default SingleHarnessActionFlow

advanced:
  @Action("generate-and-review-document")
  -> custom GenerateDocumentFlow with domain-specific Steps
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
  Add AgentActionExecutor SPI if multiple execution styles are really needed.

Phase 5:
  Add optional LangGraph4j adapter only for actions that benefit from graph
  reasoning, while preserving runtime policy/audit boundaries.
```

## Prompt Rule For Future AI Implementers

When asking an AI coding agent to implement this project, include this rule:

```text
Do not make LangGraph-style graphs the main public API.
Keep ActionRegistry, PolicyGate, ApprovalGate, AuditSink, and TraceSink as the
runtime source of truth. Use Flower Flow as the default internal execution
backend. LangGraph4j, if added later, is only an AgentActionExecutor adapter
behind the same controlled action boundary.
```

This prevents the framework from turning into a graph clone and keeps the
identity clear:

```text
Simple action declaration outside.
Controlled execution runtime inside.
Flower Flow as the default durable workflow engine.
Optional graph backends only behind the runtime contract.
```
