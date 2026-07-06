# Flower Ecosystem Vision

This document captures the long-term ecosystem direction behind Flower,
`flower-ai-harness`, and the possible future `flower-agent-runtime` project.

Former working names: `flower-agent-orchestration`, `flower-action-runtime`.

For the narrowed execution-runtime boundary, TOS analogy, AI mapping, and
control-layer placement, see
[RUNTIME_BOUNDARY_AND_LAYERS.md](RUNTIME_BOUNDARY_AND_LAYERS.md).

## Background

The software industry is entering a new era.

Historically:

```text
Human
-> Code
-> Application
```

Today:

```text
Human
-> AI
-> Code
-> Application
```

AI can already generate large amounts of software. The bottleneck is no longer
only code generation.

The new bottleneck is:

```text
control
governance
workflow
approval
recovery
observability
operations
```

The future challenge is:

```text
How do we operate AI workers safely inside real business systems?
```

## Core Philosophy

Flower is not designed to make AI smarter.

Flower is designed to make execution operable.

The primary operating principle:

```text
LLM/planner proposes.
Policy validates.
Controlled backend executes.
```

AI should not become the owner of the system. AI should become a controlled
worker operating inside a governed workflow.

The deeper identity is:

```text
Flower is an execution lifecycle runtime.
```

It sits between upper-level intent and lower-level executors. The upper system
decides what work should happen. Flower manages the execution task lifecycle.
The lower executor, equipment module, AI runner, tool runner, or domain service
knows how the work is actually performed.

## Strategic Shift

The Flower ecosystem should not compete as a generic agent framework.

That market is already crowded by systems focused on agent capability,
tool-calling, memory, graph traversal, and model integration.

The sharper position is:

```text
Controlled agent runtime for AI-driven business actions.
```

In other words:

```text
LangGraph-style systems ask:
  What can the AI do?

Flower should ask:
  What is the AI allowed to execute in this business system?
```

The target is not maximum autonomy. The target is controlled operation.

This does not mean Flower cannot use graph-like execution. Flower already has
`Flow` and `Step`, and those can model node-like execution plans. The ecosystem
decision is about the public center of gravity:

```text
Do not make graph authoring the main identity.
Make registered business actions, policy gates, approval, audit, and Flower
execution the main identity.
```

For the detailed Flower Flow default backend and optional future LangGraph4j
adapter strategy, see
[EXECUTION_BACKEND_STRATEGY.md](../architecture/EXECUTION_BACKEND_STRATEGY.md).

## Ecosystem Layers

### Layer 1: Flower

Purpose:

```text
Workflow runtime.
```

Responsibilities:

```text
task execution
process execution
step / sequence execution
state management
event handling
recovery
workflow orchestration
```

Flower owns execution mechanics and lifecycle state. It receives executable
work, waits for signals, advances explicit state, handles timeout, retry,
cancellation, failure, recovery, and reports results upward.

Flower core should remain lightweight. It should not become an AI framework,
agent graph product, worker marketplace, policy suite, or MCP server by itself.

### Layer 2: flower-ai-harness

Purpose:

```text
Reliable AI task execution.
```

Responsibilities:

```text
prompt construction
model routing
retry/refine
schema validation
model fallback
cost and budget guardrails
cancellation
AI run snapshots
AI execution tracing
test fake providers
```

`flower-ai-harness` owns the lifecycle of one AI task.

It does not own the whole business workflow.

### Layer 3: flower-agent-runtime

Purpose:

```text
Controlled business action execution.
```

Responsibilities:

```text
action definition
action registry
typed action proposal
policy decision
dry-run contract
approval boundary
idempotency contract
controlled executor
audit event model
replay-friendly snapshots
```

`flower-agent-runtime` owns the governance envelope around actions.

It does not own domain recipes. The host application owns domain recipes.

It is the center of the controlled agent runtime stack:

```text
Flower Agent Runtime
  powered by flower workflow engine
  may use flower-ai-harness for reliable AI tasks
  may use flower-agent-runtime-control after feedback patterns are proven
  secured by flower-mcp-proxy
  validated first by host applications such as ArchDox
```

### Optional Layer: flower-agent-runtime-control

Purpose:

```text
Feedback/control layer for business action execution.
```

Responsibilities:

```text
sensor result normalization
error signal aggregation
severity-based correction decisions
repeated error tracking
divergence guard
circuit-breaker style interventions
control event model
```

This layer is not part of `flower-core` and should not be built into
`flower-ai-harness`. It belongs near `flower-agent-runtime` because the control
problem is about how AI/tool results become business actions over time.

Do not implement it first. Validate a small loop in ArchDox or Agent-native-TOS,
then extract only the repeated mechanism.

### Layer 4: Host Worker Runtime

Purpose:

```text
Provide business workers inside a real application.
```

Example workers:

```text
DocumentReviewWorker
LegalReviewWorker
NarrativeWorker
GameOpsWorker
PlanningWorker
SchedulingWorker
```

A worker is not a model.

A worker is:

```text
AI
+ domain knowledge
+ workflow
+ tools or actions
+ policies
```

Workers perform business tasks inside constraints.

For now, this layer should be validated inside a host application such as
ArchDox before being generalized.

### Layer 5: Permission Gateway / MCP Proxy

Purpose:

```text
Control what workers and external tools are allowed to do.
```

Possible decisions:

```text
ALLOW
DENY
REQUIRE_DRY_RUN
REQUIRE_APPROVAL
REQUIRE_ADDITIONAL_CONTEXT
REQUIRE_STRONGER_AUTHENTICATION
```

Workers should never directly manipulate business systems. Workers request
actions. The permission gateway validates those actions before execution.

MCP should be treated as a connection standard, not as an execution authority.
An optional future module can expose Flower-controlled actions through an MCP
proxy:

```text
Agent
-> MCP tool request
-> Flower MCP proxy
-> schema validation
-> policy check
-> dry-run / approval
-> DomainAction
-> audit
```

This makes Flower a tool firewall for business systems.

Relationship to `flower-agent-runtime`:

```text
flower-agent-runtime
  = required controlled agent/action runtime
  = business execution boundary
  = decides whether a registered business action can execute

flower-mcp-proxy
  = optional MCP adapter/gateway
  = protocol and tool boundary
  = decides whether an MCP tool request can be exposed, accepted,
    filtered, or converted into an ActionProposal
```

Without MCP:

```text
Chat UI / REST API
-> flower-agent-runtime
-> Flower
-> Domain service
```

With MCP:

```text
MCP client / AI application
-> flower-mcp-proxy
-> flower-agent-runtime
-> Flower
-> Domain service or MCP server
```

The two layers intentionally share words such as allowlist, read/write, policy,
and audit, but they apply them at different levels:

```text
MCP proxy allowlist
  = which MCP tools are visible or accepted.

Agent runtime ActionRegistry
  = which business actions can execute.

MCP proxy audit
  = tool request and response trace.

Agent runtime audit
  = business action proposal, policy decision, approval, execution, result.
```

If Docker MCP Gateway or another infrastructure gateway is present,
`flower-mcp-proxy` should not duplicate server lifecycle, container isolation,
credential injection, or routing. It should add business policy: tenant/project
scope, read/write restrictions, risk evaluation, approval/dry-run requirements,
sensitive-data masking, output filtering, audit snapshots, and conversion into
registered DomainActions.

### Layer 6: Worker Operations Platform

Purpose:

```text
Operate workers in production.
```

Responsibilities:

```text
worker registry
worker lifecycle
worker traces
worker approvals
worker auditing
worker cost tracking
worker health monitoring
worker recovery
replay and regression review
```

This is the enterprise operations layer. It should emerge only after real host
applications prove the need.

## Data Plane And Control Plane

The ecosystem has two different layers.

Runtime/data plane:

```text
flower
flower-ai-harness
flower-agent-runtime
flower-mcp-proxy
```

Developer guidance plane:

```text
flower-dev-mcp
flower-lint
flower-test-support
code templates / generators
CI checks
```

Operation/control plane:

```text
dashboard
trace viewer
approval inbox
policy admin
replay/eval runner
cost monitor
worker registry UI
```

The control plane should not be built first. However, the data plane should
emit events and snapshots that make the control plane possible later.

Possible future product names:

```text
flower-dev-mcp
  - MCP tools for AI coding agents
  - Flower concepts and pattern guidance
  - flow skeleton generation
  - anti-pattern scanning
  - action boundary suggestions

flower-console
  - dashboard
  - trace viewer
  - approval inbox
  - policy admin
  - cost monitor
  - worker registry

flower-eval
  - replay
  - evaluation
  - regression test

flower-observability
  - traces
  - metrics
  - cost monitoring
```

These are not immediate implementation targets.

## AI-Native Framework Tooling

In the AI coding agent era, a framework should not only provide human-readable
documentation.

It should also provide machine-usable guidance:

```text
developer MCP
code generators
architecture rules
anti-pattern checks
test fixtures
CI validation
```

This does not mean Flower core should become an MCP product. It means the
Flower ecosystem can include tools that help AI coding agents use Flower
correctly.

The first concrete product proof should be:

```text
flower-dev-mcp + flower-check + real Flower/ArchDox usage
```

The goal of this proof:

```text
AI coding agents generate better Flower code
because Flower exposes its design rules in a form the agents can use.
```

This is small enough for a solo founder with AI assistance to attempt. It is
also meaningful enough to support a future commercial story.

The strategic conclusion:

```text
Do not start by operating a general MCP marketplace.
Start by operating the official Flower developer MCP.
```

Flower's near-term identity should be:

```text
Flower is an AI-native workflow framework.
It ships with official developer MCP support and checks so AI coding agents
can build Flower applications correctly.
```

This is a realistic scope for an individual or small team. It also positions
Flower to participate in future MCP directories, app platforms, catalogs, or
developer marketplaces run by larger ecosystem owners.

## Long-Term MCP Marketplace Option

A larger future platform is possible:

```text
User pays one platform
-> platform connects multiple framework developer MCPs
-> platform meters usage centrally
-> platform keeps a fee
-> framework MCP providers receive settlement
```

In that model, Flower would be the first validated framework MCP, not the whole
marketplace on day one.

Flower should not depend on owning this marketplace. The marketplace layer may
be owned by model platforms, IDE vendors, infrastructure companies, or developer
tool platforms. Flower's defensible position is to provide high-quality
official Flower developer MCP tooling that can be listed or integrated there.

Potential framework MCP categories:

```text
Flower developer MCP
Spring developer MCP
LangGraph developer MCP
Django developer MCP
domain-specific developer MCPs
```

The value is not raw MCP access. The value is framework-specific expertise,
templates, checks, and governance that help AI coding agents build better
systems.

This marketplace vision should remain optional until the first proof is real.

Do not build these first:

```text
multi-framework marketplace
integrated billing
provider settlement
external framework partnerships
large hosted MCP operations
enterprise security certification
```

Build these first:

```text
flower-dev-mcp
flower-check
Flower templates
ArchDox validation
```

Then package Flower's official developer MCP for whichever external catalogs or
platforms become important.

`flower-dev-mcp` is different from `flower-mcp-proxy`.

```text
flower-dev-mcp
  = design-time MCP for developers and AI coding agents
  = guides generated code toward correct Flower patterns

flower-mcp-proxy
  = runtime MCP gateway for production tool calls
  = controls external tool access through business policy
```

The developer MCP should help coding agents avoid mistakes such as:

```text
directly ticking child flows
mixing policy decisions into flower core
calling LLM providers directly instead of using flower-ai-harness
skipping ActionRegistry / PolicyGate for business actions
putting blocking long-running work inside ordinary synchronous steps
forgetting runId / traceId / audit events
combining actions that need separate approval, budget, or audit boundaries
```

MCP guidance alone is not enforcement. Real enforcement comes from combining:

```text
MCP guidance
code generators
static lint/check tools
test helpers
CI validation
```

The MCP tells the AI coding agent what correct Flower usage looks like. Checks,
tests, and CI prevent known bad patterns from being merged.

Practical packaging:

```text
flower-dev-mcp
  = MCP server for AI coding agents.

flower-check
  = CLI / Gradle plugin for static Flower pattern checks.

flower-test-support
  = reusable tests and fixtures for Flow, Step, event, recovery,
    ai-harness, and agent-runtime behavior.

CI integration
  = runs flower-check and tests on push / pull request.
```

Example usage:

```bash
flower-check
./gradlew flowerCheck
./gradlew test
```

Example CI flow:

```text
developer or AI coding agent pushes code
-> CI runs flowerCheck
-> CI runs tests
-> merge/release is blocked if checks fail
```

The intended experience is not that every user manually reviews every Flower
workflow. The intended experience is that projects install the checks once, and
the same Flower rules run locally and in CI.

## Agent vs Worker vs Action

Traditional agent architecture often looks like:

```text
Agent
-> Tool
-> Tool
-> Tool
```

Primary concern:

```text
What can the AI do?
```

The Flower-oriented architecture should look like:

```text
Worker
-> ActionProposal
-> PolicyDecision
-> DomainAction
-> Flower execution
-> Audit
```

Primary concern:

```text
How can AI safely operate inside a business system?
```

This is the key difference.

The agent is not the runtime owner. The agent is only one possible proposer.

The action is the controlled unit.

Long term, the developer experience may become annotation-driven:

```java
@AgentWorker("report-worker")
class ReportWorker {
    @Action("run-document-qa")
    ReviewResult runReview(ReviewRequest request) {
        ...
    }
}
```

That should be a convenience layer over proven runtime contracts, not the first
core abstraction. The intended direction is:

```text
simple on the outside
controlled on the inside
explicit before magic
```

See [AGENT_WORKER_ANNOTATION_MODEL.md](../architecture/AGENT_WORKER_ANNOTATION_MODEL.md).

## Runtime Defaults

Conservative defaults are part of the product identity:

```text
unknown action denied
unregistered action denied
model output treated as proposal only
write actions require dry-run when supported
high-risk actions require approval
all production actions require audit
retryable write actions require idempotency keys
raw SQL and shell tools are not production defaults
MCP tool output must be validated before model reuse
domain service performs the actual mutation
```

These defaults matter more than a clever agent graph.

## Shared Context And Events

The runtime stack needs a shared execution context before it needs a dashboard.

Minimum shared identifiers:

```text
tenantId
userId
sessionId
runId
traceId
agentId
agentProfileVersion
actionId
policyDecisionId
approvalId
```

Core events should include:

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
RunCompleted
RunFailed
```

This keeps future trace viewing, replay, cost analysis, policy review, and
approval audit possible without rewriting the runtime.

## Relationship To Existing Systems

Flower is not intended to replace:

```text
LangGraph
OpenAI Agents SDK
CrewAI
AutoGen
Semantic Kernel
Spring AI
MCP
provider SDKs
```

Those systems focus mainly on AI capability, model access, or agent/tool
composition.

The Flower ecosystem should focus on AI operations:

```text
controlled execution
policy gates
approval
audit
recovery
observability
business workflow integration
MCP/tool firewalling
```

## Long-Term Product Vision

Current software often works like this:

```text
User
-> Menu
-> Form
-> Button
```

Future business software will increasingly work like this:

```text
User
-> Worker
```

Example:

```text
User:
  Start site inspection.

Worker:
  guides inspection
  requests photos
  validates findings
  performs legal review
  generates narrative
  prepares documentation
```

The user supervises. The worker performs the work. The system controls,
records, and governs the worker.

## Strategic Position

Flower is not a generic autonomous agent framework.

Flower is not an AI model.

Flower is not a raw tool framework.

The near-term position:

```text
Controlled Agent Runtime for Java business systems.
```

The long-term ecosystem ambition:

```text
Worker Operating System for business AI.
```

The objective is not to create smarter AI.

The objective is to create safer, more operable AI workers.

## Implementation Strategy

Do not implement this whole vision as a generic framework first.

The healthy path:

```text
1. Keep Flower core focused on workflow execution.
2. Keep flower-ai-harness focused on reliable AI task execution.
3. Validate controlled worker/action patterns inside a real product first.
4. Use ArchDox as the first practical validation target.
5. Extract only repeated, domain-neutral concepts later.
6. Add MCP proxy only after real external-tool use cases appear.
7. Add developer MCP/check tooling only after Flower usage rules are clear.
8. Consider hosted plans or marketplace billing only after developer tooling
   proves real value.
```

For now, the generic `flower-agent-runtime` project should remain a concept.
The first implementation should happen inside a host application such as
ArchDox, where real worker needs, approval rules, UI states, domain services,
and recovery cases can be observed.

The smallest generic model to validate is documented in
[MINIMAL_CONTROL_MODEL.md](../architecture/MINIMAL_CONTROL_MODEL.md). It keeps
`flower-ai-harness` focused on AI task reliability and puts trace, policy,
tool/action audit, approval, and controlled execution in the agent runtime
layer.

Physical separation should follow evidence. The near-term goal is clear
responsibility boundaries, not many independent products.

The first validation questions:

```text
Are agent action proposals useful?
Is the policy gate helpful without being annoying?
Is approval UX understandable and fast enough?
Does audit/trace help diagnose real issues?
Does MCP proxy add security value beyond inconvenience?
Are LLM cost and latency acceptable?
Can failed or disputed runs be replayed from snapshots?
```

## Current Status

Status:

```text
Vision sharpened.
Old working names flower-agent-orchestration and flower-action-runtime replaced
by flower-agent-runtime.
Generic implementation deferred.
ArchDox worker runtime validation recommended first.
MCP proxy is a future optional module, not Flower core.
```
