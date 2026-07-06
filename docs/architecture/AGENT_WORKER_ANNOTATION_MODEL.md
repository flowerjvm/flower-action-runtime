# Agent Worker Annotation Model

This document records a future usability direction for `flower-agent-runtime`.

The goal is not to make annotations the core of the framework. The goal is to
make controlled business actions easy to declare while keeping the real runtime
explicit, inspectable, and governed.

```text
Simple on the outside.
Controlled on the inside.
Explicit before magic.
```

## Product Promise

The developer experience should feel closer to Spring MVC than to hand-wiring a
large workflow runtime.

In Spring MVC, a developer writes a small declaration:

```java
@RestController
class ReportController {
}
```

Spring handles routing, binding, validation, security integration, exception
handling, and response serialization behind that declaration.

`flower-agent-runtime` should eventually offer the same kind of convenience for
AI/user/system-driven business actions:

```java
@AgentWorker("report-worker")
class ReportWorker {
}
```

Behind that simple surface, the runtime should handle:

```text
action discovery
ActionDefinition creation
ActionRegistry registration
input/output schema binding
proposal validation
policy evaluation
dry-run and approval routing
runtime interlocks
controlled execution submission
idempotency and recovery metadata
audit and trace events
```

The visible API should be small. The control path must remain strong.

## Key Rule

An annotation is a declaration, not execution authority.

This must never become the model:

```text
AI output
-> annotated method called directly
-> side effect
```

The intended model is:

```text
host adapter normalizes a user, UI, REST, MCP, batch, or AI-planner request
-> ActionRegistry
-> ActionDefinition
-> PolicyGate
-> DryRun when needed
-> ApprovalGate when needed
-> RuntimeInterlock
-> controlled execution backend
   - Flower by default for durable, high-risk, or long-running actions
   - direct executor may be enough for simple low-risk actions
-> ControlledActionExecutor
-> Domain service
-> Audit / Trace / Result
```

The annotation layer only helps create the runtime definitions and invokers. It
does not bypass the runtime.

## User-Facing Shape

The user should usually write three kinds of things.

### 1. Registry Or Profile Declaration

This gives a host application or agent profile a named action catalog.

```java
@AgentRegistry("archdox")
class ArchDoxAgentRegistry {
}
```

This should stay light. It names the catalog and default profile. It should not
contain business policy logic.

### 2. Action Declaration

For low-risk actions, method-based sugar can be acceptable.

```java
@AgentWorker("document-worker")
class DocumentWorker {

    @Action("document.review")
    @ReadOnlyAction
    DocumentReviewResult review(DocumentReviewRequest request) {
        return documentReviewService.review(request);
    }
}
```

For write, external-send, financial, production-change, or high-risk actions, a
class-per-action style is safer.

```java
@Action("report.submit-for-review")
@WriteAction
@RequiresApproval
class SubmitReportForReviewAction
        implements DomainAction<SubmitReportCommand, SubmitReportResult> {

    public SubmitReportResult execute(ActionContext ctx, SubmitReportCommand input) {
        return reportService.submitForReview(ctx.userId(), input.reportId());
    }
}
```

The exact annotation names are not final. The principle is what matters:

```text
READ / LOW
  -> method sugar may be acceptable.

WRITE / HIGH / EXTERNAL_SEND / PRODUCTION_CHANGE
  -> explicit action class is preferred.
```

### 3. Policy, Approval, Audit, And Domain Services

Real policy belongs in code, not only in annotations.

Annotations can describe metadata:

```text
effect
risk
required permission
default approval hint
audit requirement
idempotency hint
```

But dynamic policy must stay in runtime policies and host application services:

```text
tenant
user
role
environment
ticket id
current business state
data sensitivity
time window
office/project scope
```

Those cannot be captured safely by static annotations alone.

## Runtime Translation

At startup, a Spring adapter may translate user declarations into explicit
runtime objects.

```text
@AgentRegistry / @AgentWorker / @Action
-> scanner
-> ActionDefinition
-> ActionInvoker
-> ActionRegistry
```

At runtime, every invocation still goes through the controlled path.

```text
ActionProposal
-> ActionRegistry.resolve(...)
-> input validation
-> ActionRequest snapshot
-> controlled execution backend
   - request received
   - policy evaluated
   - dry-run completed
   - approval requested / approved when needed
   - interlock checked
   - action executed
   - audit completed
-> ActionResult
```

The common flow can be shared by many actions. A user should not need to write a
new Flow class for every simple action.

## What AI Sees

An AI planner should not see Java classes or raw methods.

It should see a safe action catalog generated from `ActionDefinition`:

```json
{
  "id": "report.submit-for-review",
  "title": "Submit report for review",
  "effect": "WRITE",
  "risk": "HIGH",
  "inputSchema": {
    "type": "object"
  }
}
```

The model can propose:

```json
{
  "actionId": "report.submit-for-review",
  "input": {
    "reportId": "RPT-1001"
  },
  "reason": "The report appears complete and ready for manager review."
}
```

The runtime decides whether that proposal becomes execution.

```text
The planner proposes.
Policy validates.
The selected backend executes only after control checks pass.
```

## Core And Adapter Boundary

The core runtime must remain annotation-free.

```text
flower-agent-runtime-core
  = explicit contracts
  = ActionDefinition, ActionProposal, ActionRegistry, PolicyGate,
    ApprovalGate, AuditSink, TraceSink, DomainAction, ActionResult
```

Spring integration is a convenience adapter.

```text
flower-agent-runtime-spring
  = annotation scanning
  = method/class to ActionDefinition mapping
  = proxy or invoker creation
  = Spring bean integration
```

Spring Boot integration should stay thin.

```text
flower-agent-runtime-spring-boot-starter
  = auto-configuration
  = conditional defaults
  = property binding
  = optional catalog exposure
```

Flower execution should also stay behind a runtime backend boundary.

```text
flower-agent-runtime-flow
  = default backend for durable, high-risk, or long-running controlled actions
  = maps ActionProposal to Flower Flow only when Flower execution is appropriate
```

This keeps the core model usable without Spring and prevents annotation details
from leaking into the execution model.

## Relationship To flower-ai-harness

An action may use `flower-ai-harness`, but the harness is not the action
runtime.

Example:

```text
@Action("document.review")
-> agent runtime checks action policy and audit requirements
-> action executor starts document review
-> document review uses flower-ai-harness internally
-> AI result returns as the controlled action result
```

`flower-ai-harness` owns reliable AI task execution.

`flower-agent-runtime` owns whether a proposed business action may execute.

## Relationship To MCP

MCP should be treated as an adapter surface, not as the core model.

```text
MCP tool call
-> flower-mcp-proxy or MCP adapter
-> ActionProposal
-> flower-agent-runtime
-> selected controlled execution backend
```

The same registered action should be callable from chat, REST, UI, batch, or MCP
without changing the action's policy and audit path.

## Guardrails For The Annotation API

Avoid these mistakes:

```text
Putting @Action directly on large domain services.
Calling annotated methods directly from model output.
Treating annotations as the full policy engine.
Exposing raw tools such as executeSql, runShell, or callAnyHttpUrl in production.
Letting write actions skip audit or idempotency.
Making Spring annotations required by core users.
```

Preferred shape:

```text
Domain service
  = business logic.

DomainAction
  = controlled action adapter around domain logic.

ActionDefinition
  = runtime metadata and schema.

PolicyGate / ApprovalGate / Interlock
  = dynamic control decisions.

Controlled execution backend
  = direct executor for simple safe actions.
  = flower-agent-runtime-flow for durable, high-risk, or long-running actions.
```

## Extraction Rule

Do not implement annotation magic first.

Good trigger:

```text
ArchDox or another host has several real actions.
The registry/policy/audit pattern repeats.
The action metadata is stable.
The boilerplate is visible and painful.
```

Bad trigger:

```text
The annotation API looks nice.
The framework wants to appear complete.
There are no real executors yet.
The action/policy/audit model is still changing every week.
```

## Implementation Direction

Recommended sequence:

```text
1. Build explicit core contracts.
2. Build the controlled execution backend contract.
3. Build flower-agent-runtime-flow for durable/high-risk action execution.
4. Validate with real host actions.
5. Add testkit/check support.
6. Add Spring annotation convenience.
7. Add REST/MCP/catalog adapters later.
```

The framework should feel easy to use, but the ease should come from a proven
runtime model, not from hiding an unstable abstraction behind annotations too
early.
