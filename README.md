# flower-action-runtime

Controlled action runtime for AI-assisted business systems.

`flower-action-runtime` is the control boundary between "someone wants this
business action to happen" and the code that actually executes it. AI may
propose. Users may click. APIs may request. Schedulers may trigger. Every one
of them becomes the same `ActionProposal`, passes through the same gates, and
leaves the same audit trail.

The unit of control is the action, not the agent.

Project status: `0.1.0`. The core runtime is usable for early
experiments and host-application validation. APIs may still change before a
1.0 release. Artifacts are not published to Maven Central yet.

## The Side Effects Are Already There

Your application already executes business actions from many directions: a
controller creates a report, a scheduled job sends reminders, a batch import
updates records. Each entry point grew its own permission checks, its own
logging, its own idea of "should this run?".

Then an LLM arrives. It is fast, confident, occasionally wrong, and it wants
to call the same services. The first integration is always the same: parse
the tool call, invoke the service, done. It demos beautifully.

Now ask the questions an operator asks:

```text
Who decided this write was allowed?
Which actions can the AI trigger at all, and where is that list?
What happens when the model retries the same turn twice?
Where does a human approve the risky ones?
Three weeks from now, why does report 4812 exist?
```

The answers are not in any single place. They are spread across handler
switches, inline `if` statements, and log lines someone remembered to write.
`flower-action-runtime` is where those answers get one home: a small Java
runtime that makes every business action pass through registry, validation,
policy, approval, idempotency, and audit before its side effect happens.

## Before / After

Before: the tool-call handler that every AI integration starts with.

```java
// somewhere inside the chat handler
switch (toolCall.name()) {
    case "create_report" -> {
        // permission rule lives here, and differently in the REST controller
        if (!currentUser.hasRole("REPORT_WRITER")) {
            throw new AccessDeniedException("no");
        }
        String id = reportService.createDraft((String) toolCall.args().get("title"));
        log.info("AI created report {}", id); // the entire audit trail
    }
    case "send_notification" ->
        // added later, in a hurry; no check, no log
        notificationService.send((String) toolCall.args().get("to"),
                (String) toolCall.args().get("text"));
    default -> throw new IllegalArgumentException(toolCall.name());
}
```

The allowlist is a `switch`. Policy is an `if` that the next handler forgets.
A retried LLM turn creates two reports. "Approval" does not exist. The audit
trail is whatever `log.info` survived refactoring.

After: every origin submits the same proposal to the same runtime.

```java
ActionProposal proposal = new ActionProposal(
        null,                               // proposalId: generated
        "report.create",                    // must be registered, or DENIED
        ActionOrigin.AI_PLANNER,            // policy sees who is asking
        "planner-7",
        "Draft report for the site inspection request",
        0.82,                               // planner confidence
        Map.of("title", "Site inspection draft"),
        turnId,                             // idempotency key: a retry cannot execute twice
        Map.of());

ActionExecutionResult result = runtime.handle(
        proposal, ExecutionContext.of("office-a", "user-1001"));

switch (result.status()) {
    case SUCCEEDED         -> reply("Created " + result.output().get("reportId"));
    case PENDING_APPROVAL  -> reply("Waiting for approval for run " + result.output().get("runId"));
    case DENIED            -> reply("Not allowed: " + result.message());
    case VALIDATION_FAILED -> reply("Bad input: " + result.message());
    case FAILED            -> reply("Failed: " + result.message());
}
```

The permission rule moved into one `PolicyGate` that every entry point shares.
The allowlist became an `ActionRegistry`. The retry became a duplicate-key
reservation. The risky write became an approval wait instead of a hope. And
every stage emitted an audit event without anyone remembering to log.

## The Runtime, In One Screen

```text
User / UI / REST / Batch / Scheduler / MCP / AI planner
        |
        v
  ActionProposal                "report.create should happen, here is why"
        |
        v
  ActionRuntime.handle(proposal, context)
        |
        |   record-proposal        run + audit trail begins
        |   reserve-duplicate      same idempotency key cannot run twice
        |   resolve-action         unregistered action -> DENIED
        |   validate-input         host-defined input validation
        |   evaluate-policy        allow / deny / require approval
        |   execute-action         your ActionExecutor, finally
        |   record-result          run state + duplicate bookkeeping, always
        v
  ActionExecutionResult
        SUCCEEDED | PENDING_APPROVAL | DENIED | VALIDATION_FAILED | FAILED
```

Gates run in order; any gate can short-circuit; the finalize stage always
runs. One shared `ActionPipeline` is the semantic source of truth, so every
execution backend enforces identical governance.

## Mental Model

Four layers, each with one job:

```text
Intent layer        user / UI / REST / batch / MCP / AI planner
                      may only PROPOSE: everything becomes an ActionProposal

Control layer       flower-action-runtime
                      DECIDES: registry, validation, policy, approval, idempotency

Execution layer     ActionExecutor -> your ordinary domain services
                      PERFORMS: called only after every gate has passed

Record layer        RunStore + AuditSink
                      REMEMBERS: what was proposed, decided, executed, and why
```

And the objects that carry a request through those layers:

```text
ActionProposal        who wants what, with which input, why, how confident
ExecutionContext      whose execution: tenant, user, runId, traceId, metadata
ActionDefinition      what an action IS: effect, risk, allowed origins, approval default
ActionExecutor        the only way the runtime touches your domain code
ActionExecutionResult the explicit outcome every caller must branch on
ActionRun             the persistent record of one pass through the pipeline
```

- `ActionProposal`: an intent, not a command. Nothing about creating one has
  side effects.
- `ActionDefinition`: the contract that makes an action governable — its
  `ActionEffect` (read-only / write / ...), `ActionRiskLevel`, allowed
  origins, and whether approval is required by default.
- `PolicyGate` / `ApprovalGate` / `DuplicateActionPolicy` / `AuditSink` /
  `RunStore`: the seams a host application plugs its own rules into.

## Quick Start

Declare an action once. The definition is what policy reasons about; the
executor is the only path to the side effect.

```java
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionOrigin;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
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
                Set.of(ActionOrigin.USER, ActionOrigin.UI, ActionOrigin.API,
                        ActionOrigin.AI_PLANNER),
                Set.of("report:write"),   // policy input for a host PolicyGate
                true,                     // dryRunSupported
                false,                    // approvalRequiredByDefault
                true,                     // auditRequired
                "report.create.input",
                "report.create.output",
                Map.of());
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionContext ctx) {
        String title = (String) ctx.input().get("title");
        return ActionExecutionResult.succeeded(
                Map.of("reportId", reports.createDraft(title)));
    }
}
```

Then run proposals through the runtime:

```java
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import java.util.List;
import java.util.Map;

var registry = new InMemoryActionRegistry(List.of(
        new CreateReportAction(reportService)));

var runtime = new DefaultActionRuntime(registry);

var result = runtime.handle(
        ActionProposal.user("report.create",
                Map.of("title", "Site inspection draft"), "user-1001"),
        ExecutionContext.of("office-a", "user-1001"));
```

`new DefaultActionRuntime(registry)` starts with safe defaults: allow-all
input validation, the default policy gate, no-op audit, and no run
persistence. Each of those is a constructor argument you replace when the
host application has real rules.

The default `PolicyGate` already encodes the conservative baseline:

- an origin the definition does not allow is **denied**
- an AI-planner proposal for anything other than a read-only action
  **requires approval**
- a `CRITICAL` risk action **requires approval**, whatever its origin
- a `HIGH` risk action that is not read-only **requires approval**
- an action marked `approvalRequiredByDefault` **requires approval**
- everything else is allowed

## Approval And Resume

`PENDING_APPROVAL` is not an error; it is the runtime doing its job. Give the
runtime a `RunStore` and the run can wait for a human:

```java
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;

var runtime = new DefaultActionRuntime(
        registry, null, null, null, null, null, null,   // defaults
        new InMemoryRunStore());

var context = ExecutionContext.of("office-a", "user-1001");
var result = runtime.handle(aiPlannerProposal, context);
// result.status() == PENDING_APPROVAL for an AI-planner write

String approvalId = (String) result.output().get("approvalId");
String runId = (String) result.output().get("runId");

// later, from an approval UI, Slack button, or admin endpoint:
var approved = runtime.resume(
        runId,
        ApprovalDecision.approved(approvalId, "manager-42"));
// the action executes now, through the same validate -> execute -> record path
```

`ApprovalDecision.rejected(...)` denies the run; `ApprovalDecision.expired(...)`
expires it. Either way the outcome, the decider, and the reason land in the
run record and the audit trail. With `flower-action-runtime-persistence-jdbc`
the waiting run survives a restart.

## What You Stop Hand-Rolling

The cost of "just call the service" is that, one incident at a time, you
rebuild a control plane you did not mean to write — once per entry point.

| What the boundary eventually needs | Hand-rolled | With the runtime |
| --- | --- | --- |
| Only known actions can run | A `switch` per handler; new tools bypass old checks. | `ActionRegistry`; unregistered action IDs are denied before any code runs. |
| Permission and origin rules | `if` statements scattered per controller, job, and handler. | One `PolicyGate` sees every proposal with its origin, definition, and context. |
| Human approval for risky writes | Ad-hoc status column, custom endpoint, bespoke state machine. | `PENDING_APPROVAL` result plus `resume(runId, decision)`. |
| The same request arriving twice | Hope, or a unique-index exception in the middle of a side effect. | `idempotencyKey` reserved before execution by a `DuplicateActionPolicy`. |
| "Why did this happen?" | Whatever log lines survived the last refactor. | `AuditSink` events for every stage: proposed, resolved, evaluated, executed. |
| "Where is that request now?" | Grep the logs. | An `ActionRun` with an explicit status in a `RunStore`. |

## What It Is, What It Is Not

`flower-action-runtime` is:

- a controlled action execution envelope
- a registry of allowed business actions
- a policy, approval, idempotency, and audit pipeline
- one boundary shared by UI, REST, batch, MCP, and AI planners
- the bridge between AI proposals and ordinary domain services

It is not:

- an autonomous agent framework or a LangGraph replacement
- an AI model client or prompt framework
- an MCP server
- a BPMN/workflow platform
- a rules engine (plug OPA/Cerbos-style engines in behind `PolicyGate`)
- a full compliance suite

If your application has one trusted entry point and no AI in the write path,
you may not need this. The runtime earns its keep the moment more than one
kind of caller — especially a probabilistic one — can reach the same side
effects.

## Where It Fits In The Flower Ecosystem

Each Flower module owns one layer and stays out of the others:

```text
flower-core             in-JVM Flow / Step execution runtime
                          "run long-lived work as explicit, observable steps"

flower-ai-harness       reliable lifecycle for one AI task
                          "make a model call survive retries, schemas, fallback"

flower-action-runtime   policy / approval / audit boundary for business actions
                          "decide whether a proposed action may execute at all"

flower-mcp-proxy        controlled gateway for external MCP tool calls
                          (future)
```

The layering in practice: a worker uses the AI harness to review a document
reliably; the harness output becomes an `ActionProposal` such as
`report.submit`; the action runtime decides whether that proposal executes,
waits for approval, or is denied. The harness makes an AI operation reliable.
The runtime decides whether the operation's business action may happen.

## Execution Backends

The pipeline semantics live in one place; how the stages are driven is
pluggable:

- `DefaultActionRuntime` (core): direct, synchronous, in-thread. The
  reference backend.
- `flower-action-runtime-workflow`: runs the same stages as Flower
  `Flow`/`Step` for observability — dumps, listeners, admin views. Not the
  durable-wait backend.
- `flower-action-runtime-eventloop` (experimental): approval waits, deadlines,
  resume, and callback-driven execution on `flower-eventloop`.

Parity tests hold the direct and workflow backends to identical governance
behavior.

## Modules

| Module | Status | Purpose |
| --- | --- | --- |
| `flower-action-runtime-core` | Early usable | Engine-neutral pipeline, registry, policy, approval, duplicate handling, audit, and run store contracts. |
| `flower-action-runtime-workflow` | Early usable | Pipeline stages as Flower `Flow`/`Step` for observability. |
| `flower-action-runtime-persistence-jdbc` | Early usable | JDBC `RunStore` for persisting `ActionRun` state. |
| `flower-action-runtime-eventloop` | MVP | Event-loop backend for approval wait, timeout, resume, callbacks. |
| `flower-action-runtime-integration-test` | Internal | Cross-module integration tests; not a published artifact. |

## Current Guarantees And Limits

What the current implementation gets right:

- one shared `ActionPipeline` is the semantic source of truth
- direct and workflow backends are covered by parity tests
- action runs persist through `RunStore`, including JDBC
- approval wait and resume work, including through the event-loop backend
- JDBC + event-loop recovery is covered by an integration test

What it does not do yet:

- Maven Central publishing is not set up
- multi-node compare-and-set run claiming is not implemented
- annotation-based Spring convenience is planned, not implemented
- external policy engines (OPA, Cerbos) are an intentional `PolicyGate`
  integration, not bundled
- MCP proxy support is a future module, not part of core

## Build And Test

```bash
mvn test
```

This repository targets Java 21.

## Design Notes

- [Action Runtime Design Notes](docs/vision/ACTION_RUNTIME_DESIGN_NOTES.md) —
  the long-form design history
- [Runtime Boundary And Layers](docs/vision/RUNTIME_BOUNDARY_AND_LAYERS.md)
- [Execution Backend Strategy](docs/architecture/EXECUTION_BACKEND_STRATEGY.md)
- [Action Run Persistence](docs/architecture/ACTION_RUN_PERSISTENCE.md)
- [Controlled Action State Machine](docs/architecture/CONTROLLED_ACTION_STATE_MACHINE.md)
- [Minimal Control Model](docs/architecture/MINIMAL_CONTROL_MODEL.md)
- [Worker Annotation Model](docs/architecture/WORKER_ANNOTATION_MODEL.md)
- [Module Structure](docs/architecture/MODULE_STRUCTURE.md)

## License

Apache License 2.0.
