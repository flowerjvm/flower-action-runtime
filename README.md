# flower-action-runtime

Controlled action runtime for AI-assisted business systems.

`flower-action-runtime` is not an agent framework. It is the control boundary
that sits between a proposed business action and the code that may execute it.

```text
User / UI / REST / Batch / MCP / AI planner
        |
        v
ActionProposal
        |
        v
registry -> validation -> policy -> approval -> execution -> audit
```

The unit of control is the action, not the agent.

AI may propose. Users may click. APIs may request. Schedulers may trigger.
Every entry point becomes the same `ActionProposal` and passes through the same
runtime envelope before side effects happen.

Project status: `0.1.0-SNAPSHOT`. The core runtime is usable for early
experiments and host-application validation. APIs may still change before a 1.0
release. Artifacts are not published to Maven Central yet.

## Why This Exists

AI-enabled applications often start like this:

```text
LLM output
-> call a tool
-> update a database
-> send a message
```

That is easy to build, but hard to operate.

Real business systems need a stricter shape:

```text
LLM/user/system proposes an action.
The host app validates the input.
Policy decides whether it is allowed.
Approval is requested when needed.
The action runs through a known executor.
The result, failure, and audit trail are recorded.
```

`flower-action-runtime` gives that shape a small Java runtime.

## The Shape, In One Screen

```text
ActionRuntime
  -> ActionPipeline
      -> record proposal
      -> reserve duplicate key
      -> resolve registered action
      -> validate input
      -> evaluate policy
      -> execute action
      -> record result
```

The same pipeline can be driven by different backends:

- `DefaultActionRuntime`: direct synchronous reference runtime.
- `flower-action-runtime-workflow`: exposes the same stages as Flower
  `Flow`/`Step` for observability.
- `flower-action-runtime-eventloop`: experimental backend for approval waits,
  resume, timeout, and callback-driven execution.

## Quick Example

Register an action:

```java
import io.github.parkkevinsb.flower.action.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.action.runtime.ActionOrigin;
import io.github.parkkevinsb.flower.action.runtime.action.ActionDefinition;
import io.github.parkkevinsb.flower.action.runtime.action.ActionEffect;
import io.github.parkkevinsb.flower.action.runtime.action.ActionExecutor;
import io.github.parkkevinsb.flower.action.runtime.action.ActionExecutionContext;
import io.github.parkkevinsb.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

final class CreateReportAction implements ActionExecutor {
    private final ReportService reports;

    CreateReportAction(ReportService reports) {
        this.reports = reports;
    }

    @Override
    public ActionDefinition definition() {
        return new ActionDefinition(
                "report.create",
                "Create report",
                "Create a draft report from a controlled request.",
                ActionEffect.WRITE,
                ActionRiskLevel.MEDIUM,
                Set.of(ActionOrigin.USER, ActionOrigin.UI, ActionOrigin.API, ActionOrigin.AI_PLANNER),
                Set.of("report:write"),
                true,
                false,
                true,
                "report.create.input",
                "report.create.output",
                Map.of());
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionContext ctx) {
        String title = (String) ctx.input().get("title");
        String reportId = reports.createDraft(title);
        return ActionExecutionResult.succeeded(Map.of("reportId", reportId));
    }
}
```

Run it through the runtime:

```java
import io.github.parkkevinsb.flower.action.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.action.runtime.ActionProposal;
import io.github.parkkevinsb.flower.action.runtime.DefaultActionRuntime;
import io.github.parkkevinsb.flower.action.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.action.runtime.action.InMemoryActionRegistry;
import java.util.List;
import java.util.Map;

var registry = new InMemoryActionRegistry(List.of(
        new CreateReportAction(reportService)
));

var runtime = new DefaultActionRuntime(registry);

ActionProposal proposal = ActionProposal.user(
        "report.create",
        Map.of("title", "Site inspection draft"),
        "user-1001");

ExecutionContext context = ExecutionContext.of("office-a", "user-1001");

ActionExecutionResult result = runtime.handle(proposal, context);
```

With the default policy, AI-planner write actions and actions marked
`approvalRequiredByDefault` return `PENDING_APPROVAL`. A host application can
plug in its own `PolicyGate`, `ApprovalGate`, `AuditSink`,
`DuplicateActionPolicy`, and `RunStore`.

## What It Is

`flower-action-runtime` is:

- a controlled action execution envelope
- a registry of allowed business actions
- a policy, approval, idempotency, and audit pipeline
- a small runtime that can be used by UI, REST, batch, MCP, or AI planners
- a bridge between AI proposals and ordinary domain services

It is not:

- a generic autonomous agent framework
- a LangGraph replacement
- an AI model client
- a prompt framework
- an MCP server
- a BPMN/workflow platform
- a full compliance suite

## Where It Fits

```text
flower-core
  = in-JVM Flow / Step execution runtime

flower-ai-harness
  = reliable AI model-call lifecycle

flower-action-runtime
  = policy/approval/audit boundary for business actions

future flower-mcp-proxy
  = controlled gateway for external MCP tool calls
```

For example, an ArchDox worker may use an AI harness to review a document, then
submit `report.create` or `report.submit` as an `ActionProposal`. The action
runtime decides whether that proposal may execute, needs approval, or must be
denied.

## Modules

| Module | Status | Purpose |
| --- | --- | --- |
| `flower-action-runtime-core` | Early usable | Engine-neutral action pipeline, registry, policy, approval, duplicate handling, audit, and run store contracts. |
| `flower-action-runtime-workflow` | Early usable | Runs the same pipeline as Flower `Flow`/`Step` stages for observability. It is not the durable-wait backend. |
| `flower-action-runtime-persistence-jdbc` | Early usable | JDBC `RunStore` for persisting `ActionRun` state. |
| `flower-action-runtime-eventloop` | MVP | Experimental event-loop backend for approval wait, timeout, resume, and callback-driven actions. |
| `flower-action-runtime-integration-test` | Internal | Cross-module integration tests; not meant as a published runtime artifact. |

## Current Guarantees

The current implementation focuses on getting the control envelope right:

- one shared `ActionPipeline` is the semantic source of truth
- direct and workflow backends are covered by parity tests
- action runs can be persisted through `RunStore`
- approval wait and resume are available through the event-loop backend
- JDBC + event-loop recovery is covered by an integration test

Current limitations:

- Maven Central publishing is not set up yet
- multi-node compare-and-set run claiming is not implemented
- annotation-based Spring convenience is planned, not implemented
- external policy engines such as OPA/Cerbos are intentionally deferred
- MCP proxy support is a future module, not part of core

## Build And Test

When Maven is available:

```bash
mvn test
```

This repository currently targets Java 21.

## Design Notes

The old long-form README was intentionally preserved as a design note:

- [Action Runtime Design Notes](docs/vision/ACTION_RUNTIME_DESIGN_NOTES.md)

More focused documents:

- [Execution Backend Strategy](docs/architecture/EXECUTION_BACKEND_STRATEGY.md)
- [Action Run Persistence](docs/architecture/ACTION_RUN_PERSISTENCE.md)
- [Controlled Action State Machine](docs/architecture/CONTROLLED_ACTION_STATE_MACHINE.md)
- [Worker Annotation Model](docs/architecture/WORKER_ANNOTATION_MODEL.md)
- [Module Structure](docs/architecture/MODULE_STRUCTURE.md)
