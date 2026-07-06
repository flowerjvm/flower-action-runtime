# Module Structure

This document defines the intended Maven multi-module shape for a future
`flower-agent-runtime` project.

The long-term project may be structured like `flower` and `flower-ai-harness`:

```text
flower-agent-runtime
  pom.xml
  flower-agent-runtime-core
  flower-agent-runtime-flow
  flower-agent-runtime-control
  flower-agent-runtime-ai-execution
  flower-agent-runtime-ai-harness
  flower-agent-runtime-spring-ai-agent
  flower-agent-runtime-spring
  flower-agent-runtime-spring-boot-starter
  flower-agent-runtime-test
  flower-agent-runtime-observability
  flower-agent-runtime-mcp
  flower-agent-runtime-langgraph4j
```

This is not the implementation plan for the first extraction. It is only a
long-term map of possible boundaries.

The first extraction is much smaller:

```text
flower-agent-runtime-core
flower-agent-runtime-flow
flower-agent-runtime-test
```

`flower-agent-runtime-core` and `flower-agent-runtime-flow` have been
scaffolded first. Everything else should stay in host applications or
documentation until real usage proves the boundary.

In particular, `flower-agent-runtime-control` is not part of the first
extraction. It should appear only after a host application proves that the same
sensor/error/correction pattern repeats across real actions.

## Naming Decision: Control, Not PID

Use `control` in module names, not `pid`.

Reason:

```text
The framework is not implementing literal PID math.
It is using control-theory-inspired feedback concepts:
current error, accumulated error, error trend, sensors, tuning, and bounded
intervention.
```

`PID` is useful as a mental model and documentation term. The implementation
module should be broader and safer:

```text
flower-agent-runtime-control
```

This keeps room for:

```text
sensors
hard/soft validation
severity-based immediate correction
repeated error aggregation
divergence / circuit-breaker detection
control profiles
tuning sessions
model-change handling
coupling observation
```

The module name should not imply that the framework owns AI intelligence. It
only owns feedback and intervention mechanics around controlled action
execution.

## Module Responsibilities

### flower-agent-runtime-core

Purpose:

```text
Controlled business action runtime contracts.
```

Responsibilities:

```text
ActionRuntime
ActionInvocation
WorkerProfileDefinition or AgentProfileDefinition
ActionDefinition
ActionRegistry
ActionProposal
PolicyGate
PolicyDecision
ApprovalGate
ApprovalRequest
ControlledActionExecutor
ActionExecutionResult
TraceSink
AuditSink
AuditEvent
idempotency contract
runtime event contracts
```

This module owns the action boundary:

```text
proposal
-> registry
-> policy
-> approval
-> controlled executor
-> trace/audit
```

It should not depend on Spring, MCP, LangGraph4j, or host application domain
code.

### flower-agent-runtime-control

Purpose:

```text
Control-theory-inspired feedback layer for AI workers and action execution.
```

Status:

```text
Optional future module.
Validate in host applications first.
Do not include in the first generic extraction.
```

Responsibilities:

```text
Sensor
HardSensor / SoftSensor
SensorResult
AiControlError
ControlSeverity
ControlDecision
ControlProfile
ControlEvent
severity-based immediate correction policy candidate
error aggregation policy candidate
error aggregation store candidate
divergence / circuit-breaker policy candidate
PromptBlock
PromptComposer
model-change handling
domain correction vs model behavior correction separation
actuator ownership metadata
coupling signal metadata
```

This module should not know ArchDox, TOS, legal review, berth allocation, or any
other host domain.

It should also not own model provider calls, prompt optimization as a product,
RAG strategy, memory systems, or autonomous self-tuning. Those belong to AI
execution backends, host applications, or external agent frameworks.

Rule:

```text
The framework owns how errors are handled.
The host application owns what counts as an error.
```

### flower-agent-runtime-ai-execution

Purpose:

```text
Provider-neutral AI execution SPI for controlled actions.
```

Responsibilities:

```text
AiExecutionBackend
AiExecutionRequest
AiExecutionResult
AiExecutionStatus
ControlledAiExecutor
AiExecutionResultAdapter
AiControlSignalMapper
P-like wrapper around AI execution
severity-to-intervention integration
retry/refine/fallback delegation
attempt telemetry extraction
deadline / budget handoff
```

This module must not assume that every AI execution uses `flower-ai-harness`.

The runtime should be able to use different AI execution engines:

```text
flower-ai-harness
Spring AI agent utilities / agent client style executors
direct Spring AI ChatClient-based executors
host-provided AI executors
external workflow/agent services
```

The common boundary should be:

```text
controlled action
-> AiExecutionRequest
-> AiExecutionBackend
-> AiExecutionResult
-> sensors / control / trace
```

This keeps `flower-agent-runtime` from being locked to one harness
implementation.

### flower-agent-runtime-ai-harness

Purpose:

```text
Adapter for the existing flower-ai-harness project.
```

Responsibilities:

```text
FlowerAiHarnessExecutionBackend
AiExecutionRequest -> AiHarnessFlow
AiHarnessRunContext -> AiExecutionResult
HarnessResultAdapter
flower-ai-harness trace -> runtime execution event
optional ControlEvent adapter when flower-agent-runtime-control is present
```

This module wraps `flower-ai-harness`. It should not make `flower-ai-harness`
depend on `flower-agent-runtime`.

Correct direction:

```text
flower-agent-runtime-ai-harness
  -> flower-agent-runtime-ai-execution
  -> flower-agent-runtime-core
  -> flower-ai-harness

optional:
  -> flower-agent-runtime-control
```

Avoid putting business action policy, domain sensors, approval rules, or
long-term I/D governance inside `flower-ai-harness`.

### flower-agent-runtime-flow

Purpose:

```text
Default Flower Flow backend for controlled action execution.
```

Responsibilities:

```text
ActionProposal -> Flow
record proposal step
duplicate reservation step
action resolution step
input validation step
policy evaluation step
action execution step
result recording step
Flower ExecutionContext mapping
```

This module depends on `flower-agent-runtime-core` and `flower-core`.

It should not define the public action model. It only executes the core
contracts through Flower Flow when Flower execution is appropriate.

### flower-agent-runtime-spring-ai-agent

Purpose:

```text
Adapter for Spring AI agent ecosystem execution.
```

Context:

```text
Spring AI now has growing agent-side community projects and patterns such as
Agent Client, Agent Bench, and spring-ai-agent-utils. These may provide useful
agent execution, tooling, skills, benchmark, or harness-like capabilities.
```

Responsibilities:

```text
SpringAiAgentExecutionBackend
AiExecutionRequest -> Spring AI agent/client invocation
Spring AI result -> AiExecutionResult
Spring AI evaluation/bench result -> sensors or ControlSample where useful
trace/metadata bridge
deadline/budget mapping
```

This adapter should keep Spring AI agent execution behind the same runtime
control boundary:

```text
ActionRegistry
-> PolicyGate
-> ApprovalGate
-> AiExecutionBackend
-> sensors/control
-> TraceSink / AuditSink
```

Spring AI agent projects can be used as execution engines or evaluation tools,
but they should not own ArchDox/TOS business policy inside
`flower-agent-runtime`.

### flower-agent-runtime-spring

Purpose:

```text
Spring integration and annotation/proxy convenience layer.
```

Responsibilities:

```text
@WorkerProfile or @AgentProfile
@AgentWorker
@Action
@ReadOnlyAction
@DraftAction
@WriteAction
@RequiresApproval
@RequiresPermission
annotation scanner
Java interface proxy factory
method-to-action adapter
annotation-to-definition mapper
Spring bean adapters
```

This module should depend on the explicit core contracts. It should not define
the core runtime model.

Principle:

```text
Explicit contracts first.
Annotation convenience later.
```

### flower-agent-runtime-spring-boot-starter

Purpose:

```text
Spring Boot auto-configuration.
```

Responsibilities:

```text
auto-register annotated agents/workers
wire ActionRegistry / PolicyGate / TraceSink / AuditSink beans
wire optional ControlProfile / SensorRegistry beans
expose configuration properties
optionally create Agent interface proxy beans
provide conditional default no-op or deny-all beans
```

This should be thin. It should not contain domain policy.

### flower-agent-runtime-test

Purpose:

```text
Test helpers for controlled action runtime and control feedback behavior.
```

Responsibilities:

```text
fake ActionRegistry
fake PolicyGate
fake ApprovalGate
fake Sensor
fake ControlledActionExecutor
fake error aggregation store
synthetic ControlEvent builder
control policy assertions
trace/audit assertions
scenario runner fixtures
```

This module is important because host applications like ArchDox and
Agent-native-TOS should verify the control envelope with repeatable scenarios.

### flower-agent-runtime-observability

Purpose:

```text
Telemetry and tuning data contracts for operating AI workers.
```

Responsibilities:

```text
ControlSample sink contracts
ControlProfile version metadata
TuningSession contracts
distribution summary DTOs
control dashboard DTOs
retuning-needed event contracts
before/after comparison models
coupling observation models
export adapters if needed later
```

This module should provide data contracts first. Do not build a large dashboard
before real data exists.

### flower-agent-runtime-mcp

Purpose:

```text
Expose controlled registered actions as MCP tools.
```

Responsibilities:

```text
ActionRegistry -> MCP tool descriptors
MCP call -> ActionProposal
tenant/project/user scope mapping
tool allowlist bridge
input/output schema bridge
audit metadata bridge
```

This is optional and should come after the core action runtime is stable.

It must not bypass:

```text
ActionRegistry
PolicyGate
ApprovalGate
TraceSink
AuditSink
```

### flower-agent-runtime-langgraph4j

Purpose:

```text
Optional future execution backend adapter.
```

Responsibilities:

```text
LangGraph4jActionExecutor
ActionExecution -> LangGraph4j graph invocation
LangGraph4j result -> ActionExecutionResult
trace/audit bridge
tool call boundary enforcement
```

This must remain an adapter behind `AgentActionExecutor` or
`ControlledActionExecutor`.

It must not become the primary public API.

## Suggested Implementation Order

Do not implement all modules at once.

Recommended order:

```text
Phase 0:
  documentation and ArchDox / TOS validation.

Phase 1:
  flower-agent-runtime-core
  flower-agent-runtime-flow
  flower-agent-runtime-test

Phase 2:
  optional flower-agent-runtime-control only after one control loop works in a host app.

Phase 3:
  optional flower-agent-runtime-ai-execution / flower-ai-harness adapter.

Phase 4:
  flower-agent-runtime-spring
  flower-agent-runtime-spring-boot-starter

Phase 5:
  optional MCP, observability, Spring AI agent, or LangGraph4j adapters only
  after real integration pressure appears.
```

The first useful extraction should likely be:

```text
ActionDefinition
ActionProposal
ActionRegistry
PolicyGate
PolicyDecision
ActionInputValidator
IdempotencyKey
DuplicateActionPolicy
ActionRuntimeEvent
TraceSink
AuditSink
test fixtures for registry / policy / audit / idempotency
```

Avoid extracting domain-specific sensors or P/I/D-style control policies too
early.

## What Stays In Host Applications

ArchDox should keep:

```text
LegalBasisMissingSensor
UnsupportedClaimSensor
TemplateViolationSensor
DocumentSectionMissingSensor
ArchDox approval rules
ArchDox report/document policy
ArchDox prompt content
ArchDox persistence/UI specifics
```

Agent-native-TOS should keep:

```text
BerthDuplicateSensor
YardBlockingSensor
PriorityInversionSensor
PlanFeasibilitySensor
TOS operational rules
TOS optimization weights
TOS planning prompt content
TOS simulation/evaluation data
```

Generic modules should receive domain signals; they should not contain domain
truth.

## Dependency Direction

Preferred direction:

```text
spring-boot-starter
  -> spring
  -> core

flow
  -> core
  -> flower-core

ai-harness adapter
  -> ai-execution
  -> flower-ai-harness

spring-ai-agent adapter
  -> ai-execution
  -> Spring AI agent ecosystem libraries

observability
  -> core
  -> control

control
  -> core

mcp
  -> core

langgraph4j
  -> core

test
  -> core
  -> control
```

Keep `core` small and stable. Keep `control` domain-neutral. Keep adapters
replaceable.
