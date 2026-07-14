# Action Runtime Design Notes

This document preserves the original long-form project notes for
`flower-action-runtime`. The root README is now the public project introduction;
this file keeps the broader ecosystem thinking, boundary discussion, and future
module ideas for reference.

Former working names: `flower-agent-orchestration`, `flower-agent-runtime`.

This folder captures the direction for a possible future Flower ecosystem
project. The public name is `flower-action-runtime` because the runtime controls
actions, not agents. UI, REST, batch, MCP, and AI planners may all propose
actions, but they must pass through the same action boundary. Internally, the
design remains action-first: proposers submit actions, policies decide, and
approved execution runs through an engine-neutral `ActionPipeline`. The direct
runtime is the reference backend, `flower-action-runtime-workflow` makes the
same control stages observable through Flower Flow/Step, and durable waits are
left to a future event-loop backend.

For the broader ecosystem vision, see
[FLOWER_ECOSYSTEM_VISION.md](FLOWER_ECOSYSTEM_VISION.md).

For the narrowed runtime boundary, TOS analogy, AI mapping, and control-layer
placement, see [RUNTIME_BOUNDARY_AND_LAYERS.md](RUNTIME_BOUNDARY_AND_LAYERS.md).

For the smallest trace, policy, tool/action audit, and recovery-oriented
control model, see [MINIMAL_CONTROL_MODEL.md](../architecture/MINIMAL_CONTROL_MODEL.md).

For the controlled action state machine, risk classification, failure policy,
audit model, and industrial equipment-control mapping, see
[CONTROLLED_ACTION_STATE_MACHINE.md](../architecture/CONTROLLED_ACTION_STATE_MACHINE.md).

For the future Spring-style `@ActionWorker` / `@Action` usability layer, see
[WORKER_ANNOTATION_MODEL.md](../architecture/WORKER_ANNOTATION_MODEL.md).

For the Flower Flow default backend and optional future LangGraph4j adapter
strategy, see
[EXECUTION_BACKEND_STRATEGY.md](../architecture/EXECUTION_BACKEND_STRATEGY.md).

For the control-theory-inspired LLM feedback loop direction, see
[LLM_FEEDBACK_CONTROL_LOOP.md](../research/LLM_FEEDBACK_CONTROL_LOOP.md).

For the dashboard, telemetry, distribution, manual tuning, and bounded
auto-tuning strategy for those loops, see
[CONTROL_TUNING_OBSERVABILITY.md](../research/CONTROL_TUNING_OBSERVABILITY.md).

For the intended Maven multi-module structure and naming decision to use
`control` instead of `pid`, see [MODULE_STRUCTURE.md](../architecture/MODULE_STRUCTURE.md).

## One Sentence

`flower-action-runtime` is a controlled action runtime for AI-assisted business
systems.

In short:

```text
LLM/planner proposes.
Policy validates.
Controlled backend executes.
```

The starting question is not:

```text
What can AI do if we give it tools?
```

The starting question is:

```text
May this business action be executed here, now, by this actor, on this data?
```

This makes the project a policy-first action runtime, not a generic agent graph
framework. The controlled unit is the action. Agents are only one possible
source of proposals.

The deeper Flower identity is narrower than "AI framework":

```text
Flower is an execution lifecycle runtime.
```

It sits between upper-level intent and lower-level executors. In a TOS analogy,
the TOS says what work should happen, Flower manages the execution task
lifecycle, and the lower equipment module knows how to move the equipment. In
an AI system, the user or business system provides intent, Flower manages the
controlled action lifecycle, and AI/tool runners perform bounded work.

## Core Direction

The intended direction is:

```text
Controlled Action Runtime for AI-driven business actions.
```

Supporting ideas:

```text
registered business actions only
policy-gated execution
typed action proposals
dry-run before risky writes
approval-aware execution
mandatory audit traces
idempotency and replay support
budget and loop limits
recoverable execution for high-risk or long-running actions
MCP/tool firewall as an optional adapter
```

Model output is never authority. Model output is a proposal. The host
application and Flower runtime decide whether the proposal can become an
execution.

## Runtime Stack

`flower-action-runtime` should be understood as the center of a controlled
action runtime stack.

```text
User request
-> flower-action-runtime
   - ActorProfile / WorkerProfile
   - plan / action proposal
   - PolicyGate
   - RiskEvaluator
   - ApprovalGate
   - AuditEvent

uses -> AI execution backend
        - flower-ai-harness adapter
        - Spring AI agent ecosystem adapter
        - direct Spring AI ChatClient adapter
        - host-provided AI execution adapter
        - model/agent call
        - structured output
        - retry/refine where supported
        - timeout
        - token / cost tracking
        - prompt / schema versioning where supported

controlled execution backends
        -> DefaultActionRuntime
           - synchronous reference implementation
        -> flower-action-runtime-workflow
           - observable control stages through Flower Flow/Step
        -> future flower-action-runtime-eventloop
           - durable waits
           - approval resume
           - AI/tool callback waits
           - timeout / cancellation / recovery

may pass through -> flower-mcp-proxy
                  - tool allowlist
                  - read/write restriction
                  - tenant scoping
                  - secret masking
                  - output filtering
                  - tool audit

optional control layer -> flower-action-runtime-control
                          - sensors / error signals
                          - correction decisions
                          - repeated error aggregation
                          - divergence guard / circuit breaker
                          - control events
```

The call direction is not always a simple top-down stack. The action runtime may
use `flower-ai-harness`, Spring AI agent utilities, a direct Spring AI
`ChatClient`, or a host-provided AI executor to produce a planner proposal. It
may drive execution through the direct runtime, the workflow observability
backend, or a future event-loop durable-wait backend, and may route external tool
access through a future `flower-mcp-proxy`.

The responsibility model is:

```text
flower-action-runtime = central control layer
ActionPipeline       = semantic source of truth
workflow backend     = Flower Flow/Step observability layer
eventloop backend    = future durable-wait layer
AI execution backend = model/agent interaction layer
flower-ai-harness    = one supported AI execution backend
runtime-control      = optional feedback/control layer after validation
Spring AI agent libs = another possible AI execution backend
flower-mcp-proxy     = secure external tool gateway
```

## Why Not Agent Orchestration

Generic agent orchestration is already a crowded and stronger ecosystem:

```text
LangGraph
OpenAI Agents SDK
CrewAI
AutoGen
Semantic Kernel
n8n / Make
MCP-based tool ecosystems
```

Those systems usually ask:

```text
What agents, tools, memory, and graph should solve this task?
```

`flower-action-runtime` should ask:

```text
What registered business action is being requested?
Is it allowed by policy?
Does it require dry-run, approval, stronger auth, or denial?
How do we audit and recover it?
```

That difference is the product identity.

## Relationship To Existing Projects

```text
flower
  = Java flow / step / worker execution engine.
  = owns execution and recovery mechanics.

flower-ai-harness
  = reliable lifecycle for one AI task.
  = owns prompt, model call, validation, retry/refine, fallback, run trace.

flower-action-runtime
  = controlled action runtime for AI-driven business actions.
  = owns action registry, policy gate, dry-run, approval boundary,
    idempotency contract, audit events, and controlled executor.

flower-action-runtime-control
  = optional feedback/control layer after host validation.
  = owns sensors, error signals, correction decisions, repeated-error
    aggregation, divergence guards, and circuit-breaker style interventions.

host application
  = owns domain rules, domain services, persistence, UI, approval records,
    permission data, and final business decisions.
```

`flower-action-runtime` must not be added to `flower-ai-harness`.

`flower-ai-harness` makes one AI task reliable.

`flower-action-runtime` controls whether a proposed business action can execute.

`flower-action-runtime-control` may later control how strongly the runtime
intervenes when repeated errors, worsening trends, or unsafe loops appear. It
should not be implemented as part of `flower-ai-harness`.

## Responsibility Boundaries

The stack only stays healthy if each module also knows what it does not own.

### flower

Owns:

```text
workflow execution
step management
state persistence
retry / timeout
long-running process execution
resume / recovery
compensation steps
```

Does not own:

```text
LLM prompt management
agent role decisions
business policy decisions
MCP tool security decisions
```

Flower core must remain a general workflow engine.

### flower-ai-harness

Owns:

```text
model provider abstraction
prompt templates
structured output
schema validation
retry / refine
fallback
timeout
rate limit hooks
token and cost tracking
prompt versioning
model response trace
output repair
```

Does not own:

```text
business action approval
user business permissions
DomainAction execution
MCP tool exposure
workflow state management
```

The harness validates model interaction. It does not decide whether a business
action is allowed.

### flower-action-runtime

Owns:

```text
ActorProfile / WorkerProfile
ActionDefinition
ActionRegistry
ActionProposal
PolicyGate
RiskEvaluator
ApprovalGate
ControlledActionExecutor
AuditTrail
ExecutionContext
ActionResult
Replay metadata
```

Does not own:

```text
vendor-specific LLM calls
workflow engine implementation
MCP protocol implementation
raw database operations
host application domain services
```

The key rule:

```text
The runtime decides what an agent is allowed to do.
```

### flower-action-runtime-control

Future optional module.

Owns:

```text
sensor result normalization
error signal aggregation
severity-based correction decisions
repeated error tracking
divergence guard / circuit breaker decisions
control profile events
```

Does not own:

```text
domain-specific error definitions
model provider calls
prompt optimization as a product
agent memory systems
RAG strategy
autonomous self-tuning in production
```

The first control loops should live in host validation projects. Extract this
module only after the same control pattern appears in more than one real use.

### flower-mcp-proxy

Future optional module.

Owns:

```text
MCP server/tool allowlist
tool schema filtering
read-only mode
write action restriction
tenant scoping
secret masking
prompt-injection defenses
tool output filtering
rate limits
tool call audit
external tool trace
```

Does not own:

```text
agent business judgment
workflow state management
business approval workflows
LLM prompt orchestration
```

The MCP proxy should begin as a secure gateway/plugin around the action runtime,
not as an independent platform.

## Action-First Model

The unit of control is not the agent. The unit of control is the action.

```text
AI planner / User / System
-> ActionProposal
-> ActionRegistry
-> PolicyDecision
-> DryRun
-> ApprovalGate
-> DomainActionExecutor
-> AuditTrail
-> Result / Replay
```

An action should be a business capability, not a raw tool.

Long term, `flower-action-runtime` may expose a Spring-style annotation adapter
such as `@ActionWorker` and `@Action` so users can declare workers simply while
the runtime still performs registry, policy, approval, audit, trace, and
controlled execution behind the scenes. That direction is documented in
[WORKER_ANNOTATION_MODEL.md](../architecture/WORKER_ANNOTATION_MODEL.md). The
explicit action model should be validated first; annotation convenience comes
later.

The intended split is: `flower-action-runtime-core` owns explicit runtime
contracts, while `flower-action-runtime-spring` and
`flower-action-runtime-spring-boot-starter` own annotation scanning, proxy bean
creation, and automatic mapping from user declarations to runtime definitions.

Good examples:

```text
CreateInspectionReport
RunPreflightReview
RequestDocumentGeneration
SubmitReportForReview
RequestHumanApproval
CreateWorkOrderAfterApproval
NotifyCustomer
```

Bad production defaults:

```text
ExecuteSql
RunShellCommand
CallAnyHttpUrl
WriteArbitraryFile
UpdateDatabaseRow
```

Development environments may expose low-level tools. Production business
systems should expose controlled domain actions.

## Developer Experience Direction

The long-term user experience should be easy without making the runtime loose.

The developer should be able to declare controlled actions in a Spring-like
shape:

```text
declare an agent or worker
declare business actions
declare effect / risk / approval / audit hints
write small domain action adapters
```

The runtime should translate those declarations into explicit control objects:

```text
@ActionWorker / @Action
-> ActionDefinition
-> ActionRegistry
-> ActionProposal
-> PolicyGate
-> DryRun / Approval / Interlock when needed
-> controlled execution backend
   - direct executor for simple low-risk actions
   - workflow backend for observable control stages
   - future event-loop backend for durable waits
-> ControlledActionExecutor
-> Audit / Trace / Result
```

Annotations are only a convenience layer. They must not become execution
authority. Model output, REST calls, UI buttons, batch jobs, and MCP tool calls
should all enter the same controlled action boundary.

In other words:

```text
The user declares intent simply.
The runtime enforces the envelope consistently.
```

Keep the first implementation explicit. Add annotation and proxy convenience
only after real host applications prove repeated action/policy/audit patterns.

## Core Concepts

Candidate concepts, not final APIs:

```text
ExecutionContext
  - tenant id
  - user id
  - session id
  - run id
  - trace id
  - agent id
  - worker profile version
  - roles
  - metadata

ActionDefinition
  - stable action id
  - title and description for planner/tool exposure
  - input schema and output schema
  - read/write classification
  - risk level
  - required permissions
  - supports dry-run
  - supports rollback/compensation
  - default approval policy
  - audit policy
  - idempotency policy

ActionProposal
  - action id
  - typed input payload
  - proposer type: user, planner, system, MCP
  - reason/rationale
  - confidence
  - correlation id

PolicyDecision
  - ALLOW
  - DENY
  - REQUIRE_DRY_RUN
  - REQUIRE_APPROVAL
  - REQUIRE_ADDITIONAL_CONTEXT
  - REQUIRE_STRONGER_AUTHENTICATION

DomainAction
  - validate input and business state
  - dry-run expected impact
  - execute through domain service
  - optionally compensate

ApprovalRequest
  - action snapshot
  - dry-run result
  - required approver role
  - approval reason
  - expiry policy

AuditEvent
  - who/what proposed
  - what policy decided
  - what data snapshot was used
  - what executed
  - what changed
  - model/prompt metadata when AI was involved
```

The common context is especially important. Without shared `tenantId`,
`userId`, `runId`, `traceId`, `agentId`, `actionId`, `policyDecisionId`, and
`approvalId`, policy, audit, replay, and operations data will drift apart.

## Event Schema First

Dashboards can wait. Event shape should not.

The first implementation should emit replay-friendly events such as:

```text
RunStarted
ModelCallStarted
ModelCallCompleted
ActionProposed
PolicyEvaluated
DryRunCompleted
ApprovalRequested
ApprovalApproved
ApprovalRejected
ActionExecutionStarted
ActionExecutionCompleted
ActionExecutionFailed
CompensationStarted
CompensationCompleted
RunCompleted
RunFailed
```

At minimum, events should be able to carry:

```text
runId
traceId
tenantId
userId
agentId
agentProfileVersion
promptVersion
modelName
modelVersion
actionName
actionVersion
policyDecision
riskScore
approvalStatus
dryRunResult
executionResult
inputSnapshot
outputSnapshot
beforeState
afterState
cost
latency
error
```

The UI can be built later. Missing trace/audit data is expensive to recover
after production use begins.

## Runtime Defaults

The framework should be conservative by default:

```text
Unknown action -> deny
Unregistered action -> deny
Model output -> proposal only
AI_PLANNER origin -> lower trust than direct user intent
Write action -> dry-run first when supported
High-risk action -> approval required
Raw Map payload -> schema validation before policy/execution
Duplicate write proposal -> idempotency / duplicate policy required
Policy failure -> no execution
Audit sink missing -> no production execution
Idempotency key missing -> reject retryable write action
Raw SQL / shell tools -> forbidden in production profile
MCP tool output -> validate and sanitize before model reuse
```

These defaults are more important than a clever agent graph.

## MCP Proxy Position

MCP is a connection standard for exposing tools and context to AI
applications. It is useful, but raw MCP tool exposure is too permissive for
many business systems.

`flower-action-runtime` should not replace MCP.

The useful future module is:

```text
flower-mcp-proxy
```

Its role:

```text
AI application / MCP client
-> MCP tool request
-> Flower MCP proxy
-> schema validation
-> policy check
-> rate limit
-> secret masking
-> optional dry-run / approval
-> registered DomainAction or allowed MCP server
-> audit event
-> sanitized result
```

This makes Flower a tool firewall and policy proxy, not another MCP standard.

Do not build `flower-mcp-proxy` first. Validate controlled actions in a host
application first, then extract the MCP adapter only when real external-tool
needs appear.

The likely dependency direction is:

```text
flower-mcp-proxy
  depends on flower-action-runtime contracts
  depends on flower core execution primitives when it submits workflows
  may use flower-ai-harness only when tool access itself requires model calls
```

## Action Runtime vs MCP Proxy

`flower-action-runtime` and `flower-mcp-proxy` may both mention allowlists,
read/write restrictions, policy, and audit. They are still different layers.

```text
flower-action-runtime
  = controlled agent/action execution runtime
  = asks: may this business action execute?

flower-mcp-proxy
  = MCP tool access adapter / gateway
  = asks: may this MCP tool request be exposed, accepted, or converted
    into a controlled action proposal?
```

The runtime is the required core. The proxy is optional and exists only when a
host application exposes or consumes MCP tools.

Without MCP:

```text
Chat UI / REST API / system automation
-> flower-action-runtime
-> Flower workflow
-> Domain service
```

With MCP:

```text
MCP client / AI application
-> flower-mcp-proxy
-> flower-action-runtime
-> Flower workflow
-> Domain service or MCP server
```

The proxy is the front door. The runtime is the business execution boundary.

Example:

```text
MCP tool request:
  tool: update_report_step
  args:
    reportId: 10
    stepCode: SAFETY
    content: "No issue observed."
```

`flower-mcp-proxy` checks the tool boundary:

```text
Is this tool visible to the client?
Is the input schema valid?
Is the tenant/project/report scope valid?
Is this a read or write tool?
Should secrets or sensitive fields be masked?
Should the tool output be filtered before returning to the model?
```

Then it can convert the request into a runtime proposal:

```text
ActionProposal:
  actionType: UPDATE_REPORT_STEP
  origin: MCP
  payload:
    reportId: 10
    stepCode: SAFETY
    content: "No issue observed."
```

`flower-action-runtime` checks the business execution boundary:

```text
Is this user/agent allowed to update this report?
Is the report currently editable?
Does this action require dry-run?
Does this action require approval?
What risk level applies?
What audit snapshot must be recorded?
Which DomainAction executor should run?
```

The overlap is intentional but scoped:

```text
MCP proxy allowlist
  = which MCP tools are exposed or accepted.

Action runtime ActionRegistry
  = which business actions can execute.

MCP proxy read/write restriction
  = tool surface restriction.

Action runtime PolicyGate
  = business state, permission, risk, approval, and execution decision.

MCP proxy audit
  = MCP request/response audit.

Action runtime audit
  = business action proposal/decision/execution audit.
```

If Docker MCP Gateway or another MCP infrastructure gateway is used,
`flower-mcp-proxy` should sit at the business-policy layer rather than trying
to replace infrastructure concerns:

```text
flower-mcp-proxy
  = business policy, DomainAction conversion, approval/dry-run/audit snapshot.

Docker MCP Gateway
  = MCP server lifecycle, container isolation, credentials, routing,
    infrastructure-level access control, call logging/tracing.
```

## Developer MCP For Flower

`flower-mcp-proxy` is a production gateway. A different future module may be
useful for development:

```text
flower-dev-mcp
```

Purpose:

```text
Help AI coding agents and developers implement Flower-based systems correctly.
```

This module would not run production business actions. It would expose Flower
framework knowledge, templates, and checks to coding agents through MCP.

Possible tools:

```text
get_flower_concepts
recommend_flow_pattern
generate_flow_skeleton
generate_agent_runtime_action_flow
generate_ai_harness_usage
explain_agent_runtime_boundary
suggest_action_split
validate_flower_design
scan_repo_for_flower_antipatterns
```

Example guidance:

```text
Do not directly tick a child Flow from inside a parent Flow.
Submit child flows through worker.submit(...).
Persist child run ids and wait through status/event checks.
Do not put long blocking model calls directly inside ordinary steps.
Use flower-ai-harness for model calls.
Use flower-action-runtime for business action policy, approval, and audit.
Split actions when policy, approval, cost, user control, or audit boundaries differ.
```

This is useful because future Flower users may increasingly ask AI coding
agents to implement Flower workflows. A framework that only has human-readable
documentation can still be misused by generated code. A developer MCP can give
the coding agent the same design rules in a machine-usable form.

However, MCP guidance is not the same as enforcement.

```text
flower-dev-mcp
  = guides the coding agent
  = explains patterns
  = generates skeletons
  = reports likely anti-patterns

flower lint/check tools
  = inspect code and fail on known bad patterns

tests
  = prove flow behavior, recovery, retry, and event handling

CI
  = enforces checks before merge/release
```

So the practical enforcement stack is:

```text
MCP guidance
-> code generation templates
-> static checks / lint
-> test helpers
-> CI validation
```

The MCP can tell the coding agent what to do. Lint, tests, and CI are what
actually stop bad code from becoming part of a project.

Practical packaging:

```text
flower-dev-mcp
  = MCP server used by AI coding agents.

flower-check
  = CLI or Gradle plugin that scans a project for Flower anti-patterns.

flower-test-support
  = test helpers for Flow, Step, recovery, event, and action-runtime patterns.

CI integration
  = runs flower-check and tests automatically on push / pull request.
```

Example local usage:

```bash
flower-check
./gradlew flowerCheck
./gradlew test
```

Example project integration:

```kotlin
plugins {
    id("io.github.flowerjvm.flower.check")
}
```

Example CI behavior:

```text
developer or AI agent pushes code
-> CI runs ./gradlew flowerCheck
-> CI runs ./gradlew test
-> failed checks block merge/release
```

The user should not need to manually inspect every generated workflow. The
project should be configured once so that local checks and CI repeat the same
Flower rules automatically.

Possible `flower-check` findings:

```text
Flow directly drives another Flow instead of submitting it through a Worker.
Step performs a long blocking model/provider call instead of using flower-ai-harness.
Business write action bypasses ActionRegistry / PolicyGate.
Write action has no audit event.
Child flow run id is not persisted before waiting.
Await step waits without timeout or terminal-state check.
Action mixes steps that need separate approval or budget boundaries.
```

`flower-dev-mcp` should stay separate from `flower-mcp-proxy`:

```text
flower-dev-mcp
  = design-time assistant for developers and coding agents.

flower-mcp-proxy
  = runtime gateway for production MCP tool calls.
```

## Developer MCP Commercialization

`flower-dev-mcp` can become a business feature, but it should not start as a
large marketplace.

The realistic strategy is not:

```text
Flower operates a general MCP marketplace.
```

The realistic strategy is:

```text
Flower operates the official Flower developer MCP.
```

In other words:

```text
Flower is an AI-native workflow framework.
It provides official developer MCP support so AI coding agents can build
Flower applications correctly.
```

This is small enough for an individual or small team to operate, while still
leaving room to be listed later in larger MCP directories, catalogs, or app
platforms operated by companies such as model providers, IDE vendors,
infrastructure platforms, or developer platforms.

The solo-feasible first proof is:

```text
flower-dev-mcp
  - teaches AI coding agents Flower concepts
  - recommends Flow / Step / Worker patterns
  - generates correct skeletons
  - explains action-runtime and ai-harness boundaries

flower-check
  - detects known Flower anti-patterns
  - can run locally or in CI

ArchDox / Flower usage
  - validates whether these tools actually improve generated code
```

This is the important first evidence:

```text
An AI coding agent that does not know Flower well can use flower-dev-mcp
and flower-check to produce better Flower-based workflow code.
```

The initial paid value should not be "MCP call metering" by itself. MCP calls
are too small and frequent for users to reason about comfortably. A better
product shape is:

```text
Community
  - free core
  - basic docs
  - basic developer MCP
  - basic templates

Pro / Team
  - advanced pattern checks
  - repo-aware Flower architecture review
  - CI enforcement
  - premium templates
  - team-specific rules

Enterprise
  - private policy packs
  - custom Flower usage rules
  - security / audit / support
```

A broader future platform is possible:

```text
User pays one developer platform
-> platform exposes multiple framework MCPs
-> usage is measured centrally
-> platform keeps a fee
-> framework MCP providers receive usage-based settlement
```

That platform could include Flower, Spring, LangGraph, Django, or other
framework-specific developer MCPs. However, this is a long-term option, not the
first implementation target.

Flower should not assume it will own that marketplace. Large platforms may own
the marketplace layer. Flower's stronger position is to become a high-quality
framework MCP provider that those platforms can list, install, or integrate.

Hard parts that should not be attempted first:

```text
multi-framework marketplace
integrated billing
provider settlement
external framework partnerships
large hosted MCP operations
enterprise security certification
```

The practical order is:

```text
1. Make Flower usable by AI coding agents.
2. Prove it with flower-dev-mcp and flower-check.
3. Validate it in real Flower / ArchDox development.
4. Package Flower's official developer MCP for external catalogs/platforms.
5. Only then consider hosted plans or broader marketplace participation.
```

## Recipe vs Governance

Keep these apart:

```text
Recipe
  = what work happens and in what order
  = domain-specific
  = belongs to the host application

Governance
  = who may request which action
  = whether approval/dry-run/auth is required
  = how budget, audit, replay, and escalation work
  = domain-neutral enough to extract
```

The host application owns the business recipe. `flower-action-runtime` owns the
execution envelope and governance contracts.

## ArchDox Validation

ArchDox is the first practical validation target.

Current ArchDox direction:

```text
Worker chat / planner proposes an ArchDoxWorkerAction.
ArchDoxWorkerActionRegistry resolves only known actions.
ArchDoxWorkerPolicyGate checks source, context, permissions, state, and policy.
Flower can execute the durable action envelope when the action needs workflow
state, recovery, waiting, or approval handling.
ArchDox domain services perform the real mutation.
Operation events record the trace.
```

This is the right validation path because ArchDox has real business pressure:

```text
document workflow
inspection reports
preflight review
document generation
user confirmation
office policy
operation audit
existing UI plus worker service
```

Do not extract a generic framework until ArchDox proves repeated patterns.

Validation should measure more than whether the architecture compiles.

Useful validation metrics:

```text
Action proposal success rate
schema validation failure rate
policy deny rate
approval request rate
final execution rate
user-corrected execution rate
policy false positive / false negative cases
approval latency
data-changed-during-approval rate
replay success rate
average LLM calls per request
average tool calls per request
cost per request
latency per request
retry / fallback / schema repair rate
```

These metrics show whether the runtime is useful in real work, not only whether
the abstractions are clean.

## MVP Scope

The smallest useful generic scope, after ArchDox validation:

```text
ActionDefinition
ActionProposal
ActionRegistry
ActionInputValidator
PolicyGate
PolicyDecision
DryRunResult
ApprovalRequest contract
ControlledActionExecutor
ActionExecutionResult
AuditEvent
AuditSink
IdempotencyKey
DuplicateActionPolicy
LoopBudgetPolicy
```

Implementation should stay small. The goal is not to build an enterprise
platform immediately. The goal is to make dangerous AI-driven business actions
boring, visible, and controlled.

## Not In Scope

The first version should not be:

- a LangGraph clone
- a general autonomous agent framework
- a model provider SDK
- a replacement for Spring AI
- a replacement for MCP
- a vector/RAG framework
- a dashboard product
- a worker marketplace
- a full compliance/governance suite
- a runtime where AI freely edits workflow graphs
- a runtime where AI executes arbitrary tools or database operations

## Extraction Rule

Build inside ArchDox first.

Do not split every component into an independent product too early. Physical
separation can wait. Responsibility boundaries cannot.

Early development may live in one workspace or monorepo shape:

```text
flower-core
flower-ai-harness
flower-action-runtime
flower-mcp-proxy
examples / host validations
```

Extract only when the same shape repeats:

```text
same action registry shape
same policy decision shape
same dry-run/approval pattern
same audit event structure
same idempotency/replay problem
same controlled executor flow
```

Until then, this folder is a concept and direction document.

## Current Status

Status:

```text
Concept defined.
Maven parent project scaffolded.
flower-action-runtime-core created.
flower-action-runtime-workflow created as the first workflow backend.
Old names flower-agent-orchestration and flower-agent-runtime replaced by
flower-action-runtime to keep the action boundary explicit.
Generic implementation deferred.
ArchDox worker runtime validation in progress.
MCP proxy is a future optional module, not Flower core.
Minimal trace/policy/audit control model documented.
Runtime boundary narrowed around execution lifecycle management.
ActionPipeline is the semantic source of truth. The workflow backend is for
observability, and the future event-loop backend is for durable waits.
LangGraph4j is only a possible future adapter behind the same
action/policy/audit boundary.
flower-action-runtime-control is optional and deferred until a host application
proves repeated feedback/control patterns.
```

Current `flower-action-runtime-core` scope:

```text
ActionProposal
ActionDefinition
ActionRegistry
ActionInputValidator
PolicyGate
PolicyDecision
ApprovalGate
ActionExecutor
ActionExecutionResult
AuditEvent
AuditSink
TraceSink
DuplicateActionPolicy
DefaultActionRuntime
```

The core module intentionally has no dependency on Flower core, Spring, MCP,
JSON, model providers, or AI frameworks. Flower execution belongs in
`flower-action-runtime-workflow`.

Current `flower-action-runtime-workflow` scope:

```text
ActionProposal -> Flower Flow
record-proposal
reserve-duplicate
resolve-action
validate-input
evaluate-policy
execute-action
record-result
```

This module depends on Flower core and keeps Flower execution behind the same
core action/policy/audit boundary.

## License

Apache License 2.0. See [LICENSE](LICENSE).
