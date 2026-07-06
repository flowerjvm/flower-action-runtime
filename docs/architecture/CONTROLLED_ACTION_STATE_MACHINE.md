# Controlled Action State Machine

This document defines the controlled action state model for
`flower-agent-runtime`.

The model is intentionally close to industrial equipment control:

```text
command requested
-> interlock checked
-> approval if needed
-> execution
-> acknowledgement/result
-> audit and recovery
```

For AI agents, the controlled unit is not the model and not the chat message.
The controlled unit is the business action.

## 1. Industrial Control Mapping

| Industrial control | AI agent runtime |
|---|---|
| RTG / QC / YT / equipment | Agent / Worker |
| Equipment command | ActionProposal |
| Operation command | DomainAction / Tool Action |
| Operation mode | AgentProfile / ExecutionMode |
| Auto mode | Auto-executable action |
| Manual mode | Human approval required |
| Interlock | PolicyGate / Guardrail / Validation |
| Safety condition | RiskPolicy / Permission / Context check |
| PLC state | AgentRunState / ActionRunState |
| Sequence | Flower Flow |
| Step | Flower Step |
| Sensor input | ControlSensor / ToolResult / DomainSnapshot |
| ACK | ActionResult / ToolResult |
| Timeout | ActionTimeout / ToolTimeout / HarnessTimeout |
| Retry | Retry / Fallback |
| Emergency stop | Abort / KillSwitch |
| Reset | Recovery / Replay / Resume |
| Alarm | Alert / Incident |
| Operation log | AuditTrail / TraceEvent |
| HMI | Console / Dashboard / Approval UI |

The useful mental model:

```text
AI proposes the command.
Runtime checks the interlock.
Selected backend executes the sequence.
Audit records the operation.
```

## 2. Action Definition

Every executable capability should be registered as an action.

Examples:

```text
sendEmail
updateDatabase
deployService
createReport
callExternalApi
runDocumentQa
generateInspectionNarrative
moveContainer
assignEquipment
```

An `ActionDefinition` should describe:

```text
actionId
actionVersion
title
description
input schema
output schema
risk class
read/write classification
required permissions
default approval policy
dry-run support
rollback/compensation support
audit policy
idempotency policy
timeout policy
retry policy
```

Low-level tools should not be exposed directly as production business actions
unless they are wrapped by policy, schema, audit, and approval boundaries.

Bad production defaults:

```text
executeSql
runShell
callAnyHttpUrl
writeArbitraryFile
updateAnyTable
```

Good production defaults:

```text
createInspectionReport
runPreflightReview
submitReportForApproval
sendApprovedCustomerEmail
applyApprovedScheduleChange
```

## 3. Risk Classification

Recommended first risk classes:

```text
READ_ONLY
WRITE
EXTERNAL_SEND
FINANCIAL
PRODUCTION_CHANGE
```

Suggested meaning:

| Risk class | Meaning | Default policy |
|---|---|---|
| READ_ONLY | Reads data only, no mutation | Auto allowed when permission passes |
| WRITE | Mutates internal business state | Dry-run or approval depending on context |
| EXTERNAL_SEND | Sends information outside the system | Approval often required |
| FINANCIAL | Affects money, billing, refund, settlement | Admin approval or strict policy |
| PRODUCTION_CHANGE | Changes production service or operational state | Admin approval or deny by default |

Risk class is not enough by itself. Policy should also inspect:

```text
tenant
user
agent profile
action input
business state
data sensitivity
time window
current incident mode
cost / token budget
idempotency key
```

## 4. Policy Decision

The policy gate is the interlock.

Minimum useful decision set:

```text
ALLOW
REQUIRE_DRY_RUN
REQUIRE_APPROVAL
REQUIRE_ADMIN_APPROVAL
DENY
REQUIRE_ADDITIONAL_CONTEXT
REQUIRE_STRONGER_AUTHENTICATION
```

Simple default matrix:

| Risk class | Suggested default |
|---|---|
| READ_ONLY | ALLOW |
| WRITE | REQUIRE_DRY_RUN or REQUIRE_APPROVAL |
| EXTERNAL_SEND | REQUIRE_APPROVAL |
| FINANCIAL | REQUIRE_ADMIN_APPROVAL |
| PRODUCTION_CHANGE | REQUIRE_ADMIN_APPROVAL or DENY |

Production defaults should be conservative:

```text
unknown action -> DENY
unregistered action -> DENY
missing permission -> DENY
missing audit sink in production -> DENY
missing idempotency key on retryable write -> DENY
high-risk write without dry-run -> REQUIRE_DRY_RUN
high-risk action after dry-run -> REQUIRE_APPROVAL
```

## 5. State Machine

Minimum action run states:

```text
REQUESTED
PLANNED
POLICY_CHECKED
WAITING_APPROVAL
RUNNING
SUCCEEDED
FAILED
ABORTED
```

Recommended extended states:

```text
REQUESTED
PLANNED
POLICY_CHECKED
DRY_RUN_REQUIRED
DRY_RUNNING
DRY_RUN_COMPLETED
WAITING_APPROVAL
APPROVAL_REJECTED
RUNNING
SUCCEEDED
FAILED
TIMED_OUT
ABORT_REQUESTED
ABORTED
COMPENSATING
COMPENSATED
ESCALATED
```

Basic transition:

```text
REQUESTED
-> PLANNED
-> POLICY_CHECKED
-> RUNNING
-> SUCCEEDED
```

Approval transition:

```text
REQUESTED
-> PLANNED
-> POLICY_CHECKED
-> WAITING_APPROVAL
-> RUNNING
-> SUCCEEDED
```

Dry-run transition:

```text
REQUESTED
-> PLANNED
-> POLICY_CHECKED
-> DRY_RUNNING
-> DRY_RUN_COMPLETED
-> WAITING_APPROVAL
-> RUNNING
-> SUCCEEDED
```

Failure transition:

```text
RUNNING
-> FAILED
-> ESCALATED
```

Abort transition:

```text
RUNNING
-> ABORT_REQUESTED
-> ABORTED
```

Compensation transition:

```text
RUNNING
-> FAILED
-> COMPENSATING
-> COMPENSATED
```

The first framework version does not need all extended states, but the event
model should not make them impossible.

## 6. Failure Policy

Every action should have an explicit failure policy.

Possible classifications:

```text
RETRY_ALLOWED
RETRY_FORBIDDEN
FALLBACK_ALLOWED
ROLLBACK_REQUIRED
COMPENSATION_REQUIRED
HUMAN_REVIEW_REQUIRED
ESCALATE_INCIDENT
FAIL_CLOSED
```

Useful examples:

| Failure | Suggested handling |
|---|---|
| model timeout | retry/fallback if budget remains |
| schema validation failure | prompt/input repair, limited retry |
| policy denial | fail closed, do not retry unchanged |
| approval rejected | terminal rejected state |
| external send failed | retry only if idempotency key exists |
| production change failed | human review / incident |
| partial mutation | compensation or rollback |

Retry must be bounded:

```text
max attempts
max total duration
max token/cost budget
idempotency key
fallback model/tool policy
human escalation threshold
```

## 7. Audit And Trace

The runtime should record enough information to explain every controlled action.

Minimum audit questions:

```text
Who requested it?
Was it a user, system, AI agent, MCP call, or scheduled job?
What did the agent decide?
Which action was proposed?
Which input snapshot was used?
Which policy decision was made?
Was dry-run performed?
Was approval required?
Who approved or rejected?
Which tool/domain action executed?
What result came back?
What changed?
What failed?
Was retry/fallback/compensation used?
```

Recommended events:

```text
ActionRequested
ActionPlanned
PolicyEvaluated
DryRunStarted
DryRunCompleted
ApprovalRequested
ApprovalApproved
ApprovalRejected
ActionExecutionStarted
ActionExecutionSucceeded
ActionExecutionFailed
ActionTimedOut
AbortRequested
ActionAborted
CompensationStarted
CompensationCompleted
ActionEscalated
```

## 8. Runtime Responsibilities

`flower-agent-runtime` owns the controlled envelope:

```text
ActionProposal
-> ActionRegistry
-> PolicyGate
-> DryRun
-> ApprovalGate
-> ControlledExecutor
-> AuditTrail
-> ActionResult
```

The host application owns the domain recipe:

```text
what report means
what vessel task means
what data can change
what domain service executes
what business rule applies
```

The AI execution backend owns model interaction:

```text
prompt
model call
schema validation
retry/refine
fallback
model trace
```

The runtime may use `flower-ai-harness`, Spring AI agent execution, direct
Spring AI `ChatClient`, or a host-provided executor. Regardless of backend, the
action state machine and audit boundary should remain the same.

## 9. Design Rule

Do not let the agent own execution authority.

The agent may propose:

```text
I want to run action X with input Y because of reason Z.
```

The runtime decides:

```text
Is X registered?
Is Y valid?
Is the actor allowed?
Is the current state safe?
Is approval required?
Should this be denied, dry-run, executed, retried, or escalated?
```

This is the core identity:

```text
AI proposes.
Policy interlocks.
Controlled backend executes.
Runtime audits.
```
