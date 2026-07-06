# Minimal Control Model

This document defines the smallest useful trace, policy, and audit model for
`flower-agent-runtime`.

The goal is not to build an enterprise governance platform first. The goal is
to give host applications such as ArchDox a small control envelope that can be
tested against real AI worker behavior.

## Current Decision

`flower-ai-harness` does not need immediate framework-level changes for this
control model.

It already owns the lifecycle of one AI task:

```text
prompt
model call
validation
retry/refine
fallback
budget
cancellation
recovery snapshot
trace listener
fake provider testing
```

That is enough for now.

The new control work belongs in `flower-agent-runtime`, because the problem is
not "did the model call complete?" The problem is:

```text
May this proposed business action execute?
Was it controlled, approved, audited, and recoverable?
```

Do not move business action policy into `flower-ai-harness`. The harness should
remain a reliable AI task executor. The agent runtime should own business
action governance.

If feedback/control behavior becomes reusable, extract it as an optional
`flower-agent-runtime-control` module after host validation. Do not put it in
`flower-core` or make `flower-ai-harness` responsible for business governance.

## Layer Boundary

```text
flower-ai-harness
  = reliable AI task execution
  = model lifecycle, validation, retry/refine, fallback, AI trace

flower-agent-runtime
  = controlled business action runtime
  = action proposal, policy, dry-run, approval, execution, audit

flower-agent-runtime-control
  = optional feedback/control layer after validation
  = sensors, error signals, correction decisions, aggregation, circuit breakers

host application
  = domain rules, permissions, domain services, UI, persistence, final decision
```

The framework should provide contracts and events. The host application should
provide the real policy content.

Execution backend decision:

```text
Flower is the default durable backend for controlled action execution when the
action needs workflow state, waiting, approval handling, recovery, or other
long-running behavior.
Other graph/workflow engines may be added later only as executor adapters
behind the same ActionRegistry, PolicyGate, ApprovalGate, TraceSink, and
AuditSink boundary.
```

See [EXECUTION_BACKEND_STRATEGY.md](EXECUTION_BACKEND_STRATEGY.md) for the
full backend boundary.

## Smallest Useful Runtime Flow

```text
User / system request
-> Planner or host code proposes ActionProposal
-> ActionRegistry resolves ActionDefinition
-> PolicyGate evaluates request
-> optional DryRun
-> optional ApprovalGate
-> selected backend executes the controlled action
   - direct executor for simple low-risk actions
   - Flower backend for durable/high-risk/long-running actions
-> DomainActionExecutor calls host domain service
-> AuditSink records proposal, decision, execution, result
-> Result returns to user / worker / workflow
```

The AI planner is only one possible proposer. A user, REST API, scheduler,
system automation, or MCP request can also propose an action.

## Minimal Concepts

### AgentRunContext

Shared identity for one logical agent/worker run.

```text
tenantId
userId
sessionId
runId
traceId
agentId
agentProfileVersion
source
```

Keep this small. Do not put domain objects, roles, approval state, or arbitrary
metadata maps here in the first version.

### ActionDefinition

Registered business capability.

```text
actionId
actionVersion
inputSchema
outputSchema
effectType
riskLevel
requiredPermissions
supportsDryRun
defaultApprovalPolicy
requiresIdempotencyKey
auditMode
```

Suggested `effectType` values:

```text
READ
RECOMMEND
DRAFT
WRITE
EXECUTE
```

The action is the controlled unit. It should be a business capability, not a
raw tool.

Good:

```text
RUN_DOCUMENT_QA
GENERATE_REVIEW_NARRATIVE
REQUEST_REPORT_APPROVAL
UPDATE_REPORT_SECTION_AFTER_APPROVAL
```

Bad production defaults:

```text
EXECUTE_SQL
RUN_SHELL
CALL_ANY_HTTP_URL
WRITE_ARBITRARY_FILE
```

### ActionProposal

Request to execute a registered action.

```text
proposalId
runId
traceId
origin
actionId
actionVersion
input
rationale
confidence
evidenceRefs
idempotencyKey
proposedAt
```

Suggested `origin` values:

```text
USER
AI_PLANNER
SYSTEM
MCP
SCHEDULED_JOB
```

Model output is never authority. It is only an `ActionProposal`.

Origin must affect trust level.

Recommended default:

```text
USER
  -> normal permission and state checks.

SYSTEM / SCHEDULED_JOB
  -> allowed only for explicitly registered system actions.

AI_PLANNER
  -> READ / RECOMMEND may be allowed after schema and policy checks.
  -> WRITE / HIGH / EXTERNAL_SEND / PRODUCTION_CHANGE require dry-run,
     approval, or explicit host policy.
  -> raw tools are denied in production.

MCP
  -> must pass MCP gateway/tool-surface checks first, then the same
     ActionRegistry and PolicyGate path.
```

The same action can therefore receive different decisions depending on origin.

### PolicyDecision

Decision produced before execution.

```text
decisionId
proposalId
decision
reasonCodes
message
riskLevel
requiredApprovalRole
constraints
expiresAt
decidedAt
```

Suggested decisions:

```text
ALLOW
DENY
REQUIRE_DRY_RUN
REQUIRE_APPROVAL
REQUIRE_ADDITIONAL_CONTEXT
REQUIRE_STRONGER_AUTHENTICATION
```

Default policy should be conservative:

```text
unknown action -> DENY
unregistered action -> DENY
invalid input schema -> DENY
write without idempotency key -> DENY or REQUIRE_ADDITIONAL_CONTEXT
AI_PLANNER write/high-risk action without approval path -> DENY
high-risk write -> REQUIRE_APPROVAL
missing audit sink in production -> DENY
```

### Input Schema And Payload Validation

The conceptual model says "typed input", but real host applications may carry
payloads as `Map<String, Object>` for a while.

That is acceptable only if the runtime has a clear validation point:

```text
ActionProposal.input
-> ActionDefinition.inputSchema
-> ActionInputValidator
-> typed command or validated payload
-> PolicyGate
-> executor
```

Rules:

```text
Policy should not trust raw Map payload fields.
Executor should receive either a typed command or a validated payload wrapper.
Schema validation failure is a policy denial, not an executor exception.
The validation result should be recorded in trace/audit.
```

### Duplicate And Concurrent Proposals

The runtime must handle repeated proposals for the same business resource.

Examples:

```text
same report submitted twice
document generation requested twice
two workers update the same report step
AI planner proposes an action while the user already started the same action
```

Minimal contracts:

```text
idempotencyKey
resourceKey
duplicatePolicy
staleStateCheck
```

Suggested duplicate policies:

```text
REJECT
IGNORE_IF_RUNNING
RETURN_EXISTING
REPLACE_IF_SAFE
REQUIRE_REVIEW
```

This belongs beside idempotency, not in a later dashboard or MCP module.

### DryRunResult

Expected impact before execution.

```text
proposalId
actionId
wouldChange
summary
impact
warnings
blockedReasons
dataVersion
generatedAt
```

Dry-run is not required for every action, but write/execute actions should
prefer it when practical.

### ApprovalRequest

Human or stronger-policy interlock.

```text
approvalId
proposalId
decisionId
dryRunResultRef
requiredRole
reason
expiresAt
status
resolvedBy
resolvedAt
resolutionReason
```

Important rule:

```text
Approval does not skip policy.
Execution after approval must revalidate critical state.
```

If data changed during approval, the runtime should require a new decision or
new dry-run.

### ActionExecution

Runtime record for one controlled action execution.

```text
executionId
proposalId
decisionId
approvalId
actionId
status
startedAt
completedAt
resultRef
error
```

Suggested statuses:

```text
PROPOSED
POLICY_EVALUATING
DENIED
DRY_RUN_REQUIRED
DRY_RUNNING
APPROVAL_REQUIRED
WAITING_APPROVAL
APPROVED
EXECUTING
SUCCEEDED
FAILED
CANCELLED
EXPIRED
```

Keep the status model small at first. Add states only when ArchDox or another
host app needs them.

## Trace Model

Trace is for reconstruction and debugging.

It should answer:

```text
What happened, in what order, under which run/action/policy ids?
```

Minimum event envelope:

```text
eventId
eventType
occurredAt
sequence
tenantId
userId
runId
traceId
agentId
actionId
proposalId
policyDecisionId
approvalId
executionId
toolCallId
parentEventId
payloadRef or payload
error
```

Minimum event types:

```text
RunStarted
PlannerRequested
PlannerCompleted
PlannerFailed
ActionProposed
ActionInputValidated
PolicyEvaluated
DryRunStarted
DryRunCompleted
ApprovalRequested
ApprovalResolved
ActionExecutionStarted
ActionExecutionCompleted
ActionExecutionFailed
RunCompleted
RunFailed
RunCancelled
```

If tools are involved:

```text
ToolCallRequested
ToolCallAllowed
ToolCallDenied
ToolCallCompleted
ToolCallFailed
ToolResultSanitized
```

Trace events should be append-only. The first implementation can write to an
in-memory sink or host-provided persistence, but the shape should be stable.

## Policy Model

The first `PolicyGate` should be a simple interface, not a full policy
language.

Sketch:

```java
public interface PolicyGate {
    PolicyDecision evaluate(PolicyContext context);
}
```

`PolicyContext` should contain:

```text
AgentRunContext
ActionDefinition
ActionProposal
actor permissions supplied by host
resource state supplied by host
prior dry-run result when available
approval state when available
```

The runtime should not know how ArchDox permissions work. ArchDox should adapt
its own permissions and document state into `PolicyContext`.

Policy rules should be side-effect-light:

```text
Good:
  inspect context
  inspect host-provided permission/resource state
  return decision

Avoid:
  directly executing action
  directly mutating domain state
  directly calling model provider
```

## Tool vs Action

Keep tool and action separate.

```text
Tool
  = capability surface exposed to AI or runtime
  = may read data, retrieve context, or request an operation

Action
  = registered business capability that may execute
  = governed by ActionRegistry and PolicyGate
```

Examples:

```text
Tool: get_report_snapshot
Action: RUN_DOCUMENT_QA

Tool: search_legal_rules
Action: REQUEST_LEGAL_REVIEW

Tool: update_report_section
Action: UPDATE_REPORT_SECTION_AFTER_APPROVAL
```

In production, raw tool calls that mutate state should usually be converted
into `ActionProposal` and pass through policy.

`flower-mcp-proxy` can own MCP protocol/tool exposure later. The agent runtime
should own the business action decision.

## Audit Model

Audit is for accountability.

It should answer:

```text
Who/what proposed this?
What did policy decide?
What was approved?
What executed?
What changed?
Why was it allowed or denied?
```

Minimum `AuditEvent` fields:

```text
auditId
eventType
occurredAt
tenantId
userId
runId
traceId
agentId
actionId
proposalId
policyDecisionId
approvalId
executionId
summary
decision
reasonCodes
inputSnapshotRef
dryRunSnapshotRef
beforeSnapshotRef
afterSnapshotRef
resultSnapshotRef
redactionApplied
error
```

Do not store secrets or unrestricted model/tool output blindly in audit
events. Prefer redacted snapshots or references to host-owned records.

Suggested first sink:

```java
public interface AuditSink {
    void append(AuditEvent event);
}
```

Production default:

```text
If audit is required and AuditSink is missing, deny execution.
```

## Relationship To `flower-ai-harness`

The agent runtime may use `flower-ai-harness` for planning or analysis:

```text
User asks for work
-> agent runtime starts run
-> ai-harness produces structured proposal
-> agent runtime validates proposal as ActionProposal
-> policy gate decides
-> selected backend executes approved action
   - Flower when durable workflow execution is needed
```

But `flower-ai-harness` should not grow:

```text
ActionRegistry
PolicyGate
ApprovalGate
business AuditSink
DomainActionExecutor
MCP allowlist
```

Those belong to `flower-agent-runtime` or the host application.

## ArchDox Validation First

Do not implement the generic runtime first.

Validate the model inside ArchDox using 20-30 small scenarios:

```text
document QA proposal
legal review proposal
narrative generation proposal
unauthorized document access
write action requiring approval
tool timeout
invalid structured proposal
data changed during approval
duplicate/idempotent write
cancelled run
```

For each scenario, record:

```text
expected action
forbidden action/tool
expected policy decision
approval requirement
required audit event types
final state
latency/cost budget
```

Only extract generic code when the same pattern repeats across scenarios.

## MVP Interfaces

The smallest framework-level surface after ArchDox validation:

```text
ActionDefinition
ActionRegistry
ActionProposal
ActionInputValidator
PolicyGate
PolicyContext
PolicyDecision
DryRunResult
ApprovalRequest
ControlledActionExecutor
ActionExecutionResult
ActionRuntimeEvent
TraceSink
AuditEvent
AuditSink
IdempotencyKey
DuplicateActionPolicy
```

Avoid in the first extraction:

```text
policy DSL
dashboard
generic autonomous planner
MCP proxy implementation
marketplace
complex memory system
multi-agent graph engine
```

## Practical Rule

Start with contracts that make failure visible.

The first version does not need to prevent every bad AI outcome. It must make
bad outcomes:

```text
deniable
stoppable
auditable
replayable
testable
```

That is the control-system view of `flower-agent-runtime`.
