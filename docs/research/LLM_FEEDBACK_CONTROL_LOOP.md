# LLM Feedback Control Loop

This document captures a possible long-term control model for LLM-based
workers in the Flower ecosystem.

The goal is not to copy PID control formulas into LLM workflows.

The goal is to translate the useful control-system idea:

```text
Handle current error, accumulated error, and error trend differently.
```

For AI workers, this becomes:

```text
P-like feedback = immediate correction for the current output.
I-like feedback = persistent correction for repeated bias.
D-like feedback = instability detection from worsening error trend.
```

This can become a practical feedback/control layer around
`flower-agent-runtime`. It may wrap or observe `flower-ai-harness`, but it
should not turn the harness itself into a broad governance engine.

This is a research and design note, not a public API commitment. The first
implementation should be much smaller:

```text
P-like idea -> severity-based retry/refine/fail-closed policy
I-like idea -> error aggregation plus persistent correction candidate
D-like idea -> divergence guard or circuit breaker
```

The first useful experiment is one repeated, measurable error such as
`wrong_locale_date_format` over a small run set. The framework should prove that
one loop before exposing broad control abstractions.

For the observability, distribution, dashboard, manual tuning, and bounded
auto-tuning strategy required to operate these loops, see
[CONTROL_TUNING_OBSERVABILITY.md](CONTROL_TUNING_OBSERVABILITY.md).

## Why This Matters

Many agent frameworks implement retry logic in an ad-hoc way:

```text
try model call
if invalid, retry 3 times
if still invalid, fail
```

That is useful but shallow.

Business AI workers need stronger control:

```text
detect the current mistake
measure repeated mistakes
detect worsening behavior
choose the right intervention axis
stop unsafe loops
preserve what was learned
make the next run more stable
```

This is closer to a control-system view than a simple retry loop.

## Important Constraint

LLMs are not linear motors.

Do not assume:

```text
same input -> same output
continuous error surface
stable proportional response
direct mathematical PID tuning
```

LLM behavior is discrete, stochastic, context-sensitive, model-dependent, and
nonlinear.

So the framework should not implement literal PID math first.

Instead, it should implement a control structure inspired by PID:

```text
current error
accumulated error
error trend
multi-axis intervention
bounded loop control
```

## Sensor

In this document, a sensor means:

```text
a domain or runtime evaluator that observes an AI output, action, or result
and emits a typed error signal, score, or measurement.
```

The controller does not control reality directly. It controls what the sensors
measure.

Important rule:

```text
You control what you measure.
```

If the sensor is good, the feedback loop can drive the worker toward better
behavior. If the sensor is wrong, the feedback loop can efficiently optimize
the wrong thing.

Examples:

```text
ArchDox sensors:
  LegalBasisMissingSensor
  UnsupportedClaimSensor
  TemplateViolationSensor
  DocumentSectionMissingSensor

Port-operation sensors:
  BerthDuplicateSensor
  YardBlockingSensor
  PriorityInversionSensor
  EquipmentTravelCostSensor
  PlanFeasibilitySensor

General runtime sensors:
  SchemaValidationSensor
  PolicyViolationSensor
  ApprovalRejectionSensor
  LatencyBudgetSensor
  CostBudgetSensor
```

Sensor design is a domain problem. The framework can define the sensor
interface and control signal format, but the host application must define what
counts as a good or bad outcome.

## Hard Sensors And Soft Sensors

Not every sensor should become a weighted score.

Use two categories.

```text
hard sensor
  = detects a non-negotiable violation.
  = failure should reject, block, require approval, or fail closed.

soft sensor
  = measures quality, cost, efficiency, style, or optimization score.
  = result can be compared, ranked, or improved.
```

Recommended evaluation order:

```text
1. Run hard sensors first.
2. Reject or escalate hard violations.
3. Run soft sensors only on valid candidates.
4. Use soft scores for ranking and feedback.
```

Do not let a high soft score compensate for a hard violation.

## Error Signals

The feedback loop needs explicit error signals.

Examples:

```text
schema_invalid
required_field_missing
unsupported_claim
hallucinated_citation
wrong_locale_format
unsafe_action_proposed
unauthorized_tool_requested
approval_rejected
low_confidence
tool_result_ignored
cost_budget_exceeded
latency_budget_exceeded
loop_divergence
```

Each error should have:

```text
errorCode
severity
source
detectedAt
runId
actionId
modelId
promptVersion
schemaVersion
attemptNo
evidence
suggestedIntervention
```

Do not rely on vague natural-language critique alone. Natural-language critique
can be stored as evidence, but the controller needs typed error codes.

## P-Like Feedback: Current Error

P-like feedback handles the current output.

Question:

```text
What is wrong with this output right now?
```

Examples:

```text
Output is not valid JSON.
Required field is missing.
The result cites a rule that was not in context.
The action proposal is too risky.
The generated document fails domain validation.
```

Possible interventions:

```text
minor prompt repair
structured regeneration
schema repair
critique/refine turn
switch to stricter prompt template
lower temperature
fallback to safer model
stop and fail safely
request human review
```

Severity can control intervention strength:

```text
low severity
  -> add small correction instruction and retry

medium severity
  -> refine with critique and stricter schema reminder

high severity
  -> discard output, change model/prompt, or stop

critical severity
  -> block execution and emit audit/failure event
```

This maps naturally to `flower-ai-harness` retry/refine behavior.

## Concrete P Loop

P-like control is usually the first thing to implement because many harnesses
already contain a primitive version of it:

```text
generate output
validate output
if invalid, send feedback and retry
```

The improvement is to make the intervention proportional to the error
severity.

Do not treat all failures the same.

Suggested mapping:

```text
minor error
  example:
    small format issue, minor missing optional field
  intervention:
    preserve most of the output
    give one precise correction instruction
    retry or repair only the broken part

medium error
  example:
    one logical error, weak evidence, missing required field
  intervention:
    explain the failed validation
    ask for focused regeneration
    keep valid context but tighten the instruction

severe error
  example:
    hallucinated evidence, domain consistency violation, unsafe action proposal
  intervention:
    discard output
    regenerate from a cleaner context
    lower temperature or switch prompt/model
    possibly require human review

critical error
  example:
    unauthorized write action, policy violation, dangerous tool request
  intervention:
    fail closed
    emit audit event
    do not retry blindly
```

Port-operation example:

```text
slightly inefficient berth assignment
  -> ask the model to reconsider with a cost hint

same berth assigned to two vessels
  -> reject immediately and regenerate under a hard consistency rule
```

This is different from:

```text
if anything is wrong, retry from scratch 3 times
```

That style wastes tokens on small errors and underreacts to serious errors.

### P Failure Mode: Oscillation

If the P intervention is too strong, the agent can oscillate.

Example:

```text
fix format
-> content gets worse
fix content
-> format gets worse
fix format
-> content gets worse again
```

This is the LLM version of overshoot.

Mitigation:

```text
use graded intervention
avoid rewriting everything for small errors
preserve known-good parts of the output
limit correction scope
detect repeated A/B correction patterns
escalate to D-like damping if oscillation appears
```

## I-Like Feedback: Accumulated Error

I-like feedback handles repeated bias.

Question:

```text
What mistake keeps happening across attempts, runs, or scenarios?
```

Examples:

```text
The model repeatedly uses US date format in Korean documents.
The model repeatedly omits legal basis fields.
The model repeatedly overuses confident language when evidence is weak.
The model repeatedly proposes a write action when only draft is allowed.
The model repeatedly ignores a specific tool result.
```

Possible interventions:

```text
update prompt template constraints
add durable task memory
add domain-specific negative examples
adjust model routing rule
add stricter validator
add new policy rule
add regression test case
deprecate a prompt version
```

This is the most important first axis to test.

The reason:

```text
Repeated model errors are often not random noise.
They are systematic prompt/model/task bias.
```

The framework can track these repeated errors and recommend persistent fixes.

## Concrete I Loop

I-like control is about repeated error, not one-time error.

One sentence:

```text
If an agent repeats the same kind of mistake, count it, classify it, and add a
persistent correction so the same mistake becomes less likely in future runs.
```

The minimal loop has four stages.

### 1. Measure

There must be a validation or consistency check that can say:

```text
this output/action is wrong
```

Examples:

```text
schema validator rejects output
domain validator detects illegal state
policy gate denies action
approval is repeatedly rejected for the same reason
tool result contradicts model conclusion
```

Port-operation examples:

```text
two vessels assigned to the same berth
container placed where it cannot later be retrieved
priority job delayed behind lower-priority work
```

Without measurement, there is no I loop. The first implementation question is:

```text
Can the system automatically detect the mistake, or does a human only notice it
after the fact?
```

If automatic detection exists, the I loop can start at classification. If not,
measurement is the first feature to build.

### 2. Classify

The error must get a stable tag.

Do not only count:

```text
wrong
```

That does not say what should be corrected.

Use specific error tags:

```text
berth_duplicate_assignment
yard_unreachable_stack_position
priority_inversion
wrong_locale_date_format
missing_legal_basis
unsupported_claim
write_action_without_approval
tool_result_ignored
```

I-like correction needs repeated occurrences of the same error type.

### 3. Accumulate

Keep counters by useful dimensions:

```text
actionId
workerId
modelId
promptVersion
schemaVersion
errorCode
```

Example rule:

```text
if same actionId + promptVersion + errorCode occurs 3 times in 20 runs:
  repeated bias detected
```

The threshold is the I-like gain.

If the threshold is too low:

```text
one random failure becomes permanent correction
```

If the threshold is too high:

```text
the system never learns
```

Start with simple thresholds and tune from real data.

### I Store Categories

I-like accumulated corrections must be separated by what they represent.

Do not store every correction in one bucket.

Use at least two categories:

```text
domain correction store
  = model-independent domain knowledge or process facts.
  = survives model changes.

model behavior correction store
  = model-specific habits, weaknesses, or repeated output style problems.
  = should be reset, disabled, or revalidated when the model changes.
```

Domain correction examples:

```text
this berth cannot accept large vessels
this report type requires legal basis references
this customer segment needs neutral tone
this operation mode requires approval before write
```

Model behavior correction examples:

```text
model A often uses US date format
model B overstates confidence
model C ignores tool result when context is long
model D over-apologizes in customer replies
```

Model change rule:

```text
when model changes:
  keep domain correction store
  reset or quarantine model behavior correction store
  rerun tuning for gains and thresholds
  start accumulating new model behavior evidence
```

This preserves real domain assets while avoiding stale corrections from an old
model.

Analogy:

```text
Changing a motor requires retuning gains.
It does not erase process knowledge about the machine or production line.
```

### P/I/D Memory And Model Changes

Model-change handling is mostly an I-like concern because I is the only part
that stores long-term accumulated corrections.

Memory profile:

```text
P-like correction
  time horizon:
    current attempt
  memory:
    none or very short-lived
  model change impact:
    no accumulated store to migrate
    retune intervention strength if needed

D-like trend control
  time horizon:
    recent window
  memory:
    sliding window of recent attempts/runs
  model change impact:
    old model trend naturally falls out of the window
    reset window on model change if cleaner separation is needed
    retune sensitivity if needed

I-like accumulated correction
  time horizon:
    historical / persistent
  memory:
    durable counters and corrections
  model change impact:
    domain corrections survive
    model behavior corrections reset/quarantine/revalidate
    thresholds/gains should be retuned
```

Do not confuse two separate issues:

```text
stored accumulated knowledge
  = mostly I-like.
  = must be split into domain correction and model behavior correction.

control gains / thresholds
  = P, I, and D can all be model-dependent.
  = should be retuned or revalidated when model changes.
```

So the practical model-change rule is:

```text
P:
  keep mechanism, retune severity-to-intervention strength.

D:
  keep mechanism, clear or naturally roll the recent trend window,
  retune divergence/oscillation sensitivity.

I:
  keep domain correction store,
  reset/quarantine model behavior correction store,
  retune accumulation thresholds.
```

### 4. Correct

When repeated bias is detected, do not only retry the same run.

Add a persistent correction:

```text
prompt constraint
task memory
negative example
validator rule
policy rule
workflow pre-check
model routing rule
regression test case
```

Example:

```text
berth_duplicate_assignment repeated 3 times
-> add persistent instruction:
   "Before assigning a berth, query current berth occupancy and reject occupied
    berths."
-> optionally enforce this as a pre-action domain validator
```

Important distinction:

```text
P correction = this run only.
I correction = future runs too.
```

### I Failure Mode: Integral Windup

I-like control can overcorrect.

If every repeated error becomes a permanent prompt constraint, the context can
fill with rules:

```text
do not do this
do not do that
also never do this
except when...
```

The worker becomes rigid, confused, or contradictory.

This is the LLM version of integral windup.

Anti-windup rules:

```text
limit the number of active persistent corrections
give corrections priority and scope
expire old corrections unless they are still useful
deduplicate similar corrections
detect contradictory corrections
review corrections before permanent promotion
prefer validators/policies over prompt clutter when a rule is hard safety
```

Simple first version:

```text
max active corrections per action = N
when limit is exceeded, remove the oldest low-impact correction
```

## D-Like Feedback: Error Trend

D-like feedback handles worsening behavior.

Question:

```text
Is the run becoming less stable over time?
```

Examples:

```text
Attempt 1 had one schema issue.
Attempt 2 added hallucinated citations.
Attempt 3 proposed an unauthorized tool.
```

That is divergence.

Possible interventions:

```text
stop retry loop early
reset context
remove accumulated conversational noise
fallback to safer model
switch from refine to fresh regeneration
request human review
fail closed
```

This is useful for agent loops because LLM agents can compound their own
mistakes.

The controller should detect:

```text
error severity increasing
number of errors increasing
tool misuse increasing
confidence decreasing
cost increasing without quality improvement
loop repeats without new evidence
```

## Concrete D Loop

D-like control is about trend, not just size.

One sentence:

```text
Watch whether errors are getting worse, oscillating, or repeating without
progress, then damp the loop before it becomes expensive or unsafe.
```

D-like control mainly catches two patterns.

### Divergence

The run is getting worse over attempts.

Example:

```text
attempt 1:
  missing optional field

attempt 2:
  missing required field and weak evidence

attempt 3:
  hallucinated source and unauthorized action proposal
```

The error severity is increasing. The controller should stop the current
strategy before the run consumes more cost or creates risk.

Possible interventions:

```text
stop retry loop early
reset context
remove accumulated conversational noise
switch from refine to fresh regeneration
fallback to safer model
ask for human review
fail closed
```

Port-operation example:

```text
agent assigns berths three times and each plan becomes less feasible
-> stop local repair
-> re-plan from clean state or escalate
```

### Oscillation

The run alternates between failure modes.

Example:

```text
attempt 1:
  format valid, content wrong

attempt 2:
  content fixed, format invalid

attempt 3:
  format fixed, content wrong again
```

The output is not converging. The controller should damp the correction.

Possible interventions:

```text
reduce correction strength
fix one dimension at a time
freeze known-good parts
switch to structured partial repair
change prompt strategy
escalate if oscillation persists
```

Port-operation example:

```text
agent moves container A, moves it back, then moves it again
-> oscillation detected
-> freeze state and require a different planning strategy
```

### D Failure Mode: Noise Sensitivity

LLM outputs naturally vary.

One strange attempt does not prove a trend.

D-like control should be conservative:

```text
ignore one-off spikes
use a rolling window
require repeated worsening before intervention
compare severity trend, not only raw count
use D after P/I behavior is already observable
```

Do not let D-like logic constantly interrupt healthy runs.

## Multi-Axis Control Inputs

A motor may have a clear control input such as current or voltage.

LLM systems have multiple control inputs.

Possible intervention axes:

```text
prompt template
system instruction
retrieved context
memory
temperature
model selection
tool availability
retry/refine policy
schema strictness
validator strictness
approval requirement
budget limit
human handoff
workflow branch
```

This is closer to multi-input, multi-output control than single-axis PID.

Changing one axis can affect other outputs:

```text
lower temperature
  -> more stable format
  -> less creative wording

more context
  -> better evidence
  -> higher cost and possible distraction

stricter schema
  -> safer integration
  -> higher retry rate

fallback model
  -> better reasoning
  -> higher cost and latency
```

The controller should choose small, bounded interventions first.

## Coupling Between Control Axes

LLM control axes are often coupled.

Coupling means:

```text
one control input changes multiple output qualities at the same time
```

Example:

```text
lower temperature
  -> improves consistency and format stability
  -> may reduce creativity and exploration

higher temperature
  -> may improve alternative generation and optimization exploration
  -> may increase hallucination, schema errors, or policy violations

more prompt constraints
  -> improves safety and consistency
  -> may reduce flexibility and optimization quality

stricter schema validation
  -> improves integration safety
  -> may increase retries, latency, and cost
```

If two independent controllers try to control the same actuator, they can fight.

Bad example:

```text
safety controller:
  consistency error detected -> lower temperature

creativity controller:
  output too conservative -> raise temperature
```

Both are trying to move the same control input in opposite directions.

Do not let multiple controllers own the same actuator without arbitration.

## Actuator Ownership

Each control input should have an owner or priority rule.

Suggested first policy:

```text
safety owns hard constraints
format owns schema repair
cost owns budget caps
latency owns deadline fallback
optimization owns soft prompt goals
creativity owns optional candidate diversity
```

For high-risk business systems, safety should dominate:

```text
hard safety constraint
  > policy / permission
  > approval requirement
  > deadline / cost
  > output quality
  > creativity / exploration
```

This avoids controller fights.

If a lower-priority objective wants to change an actuator owned by a
higher-priority objective, it must operate inside the allowed range.

Example:

```text
safety policy sets maxTemperature = 0.5 for a write action.
optimization may choose temperature between 0.2 and 0.5.
optimization may not raise temperature to 0.9.
```

This is the practical version of:

```text
one objective sets the boundary
another objective optimizes inside the boundary
```

## Prompt Block Control

For LLMs, one of the most important control inputs is the prompt itself.

Prompt control means:

```text
the runtime composes prompt blocks based on current error, accumulated error,
trend, policy, and action risk
```

Recommended block structure:

```text
[Absolute Rules]
  non-negotiable constraints
  mirrored by validator/policy where possible

[Optimization Goals]
  desired improvements inside the absolute rule boundary

[Task Context]
  current domain state and user request

[I Persistent Corrections]
  repeated-error corrections promoted from accumulated feedback
  versioned, scoped, prioritized, and bounded

[P Temporary Correction]
  current-run correction from the last failed attempt
  removed after the retry/repair cycle

[D Trend Warning]
  only present when divergence or oscillation is detected
  tells the worker to use a safer or reset strategy
```

Example:

```text
[Absolute Rules]
- Do not assign an occupied berth.
- Do not propose a write action without approval.

[Optimization Goals]
- Within the absolute rules, improve equipment movement efficiency.
- Prefer plans that reduce waiting time.

[I Persistent Corrections]
- Before assigning a berth, query berth occupancy first.
  Reason: berth_duplicate_assignment occurred 3 times.

[P Temporary Correction]
- Previous attempt assigned vessel V-102 to occupied berth B-3.
  Exclude occupied berths and regenerate only the assignment section.

[D Trend Warning]
- Recent attempts are oscillating between valid format and invalid content.
  Preserve valid fields and modify only invalid fields.
```

P/I/D are not only numbers. They can change which prompt blocks appear and how
strongly those blocks are worded.

Important distinction:

```text
hard safety belongs in validators, policy gates, approval gates, and schema.
prompt blocks can reinforce safety, but they are not the only safety layer.
```

Do not rely on prompt text alone for non-negotiable business constraints.

## Coupling Management Strategy

Start simple.

Recommended order:

```text
1. Put safety and legality into hard constraints.
2. Put optimization and creativity into soft goals.
3. Assign actuator ownership.
4. Record every actuator change and every affected output metric.
5. Look for coupling in tuning data.
6. Only later consider multi-variable control policies.
```

Early implementation should not attempt a full MIMO controller.

First useful version:

```text
temperature:
  owned by safety/consistency for high-risk actions

prompt soft goals:
  owned by optimization/creativity

schema strictness:
  owned by integration safety

model routing:
  owned by risk/cost/latency policy
```

When coupling appears, prefer boundary-first handling:

```text
safety defines allowed range
optimization searches inside that range
```

## Latency And Multi-Rate Control

LLM calls are slow compared to classical control loops.

Servo control can run in milliseconds or faster. A complex model call can take
seconds or tens of seconds. Therefore, do not imagine the LLM feedback loop as
a high-frequency servo loop.

If every decision synchronously performs:

```text
LLM call
-> validation
-> critique
-> LLM retry
-> validation
-> another retry
```

then real-time decision paths will become too slow.

The solution is multi-rate control:

```text
fast loop:
  deterministic validation, policy checks, simple P-like correction

medium loop:
  bounded LLM retry/refine for actions that can wait

slow loop:
  I-like accumulated correction and D-like trend monitoring

feedforward path:
  precompute likely decisions before the real-time moment arrives
```

Do not run every control loop at the same speed.

### Fast Path

The fast path should avoid extra model calls whenever possible.

Good fast-path checks:

```text
schema validation
required field validation
hard policy deny
permission check
known unsafe action check
domain consistency check
budget/deadline check
cache lookup
precomputed candidate selection
```

For P-like control, this means:

```text
minor or obvious errors
  -> deterministic repair or immediate reject

severe but clear violations
  -> fail closed or branch to approval/escalation

ambiguous semantic errors
  -> only then consider another LLM call
```

This keeps most P-like interventions cheap.

### Slow Path

I-like and D-like control should usually be asynchronous.

I-like accumulated correction:

```text
record error after the run
update counters in the background
recommend persistent correction later
apply to future runs after review/versioning
```

D-like trend control:

```text
observe rolling windows
detect divergence or oscillation
raise safety/retuning signal
intervene only when thresholds are crossed
```

These should not block every normal request.

### Risk-Based Routing

Latency policy should depend on risk.

```text
low-risk / high-frequency:
  prefer deterministic rules, cached results, small models, or no LLM retry

medium-risk:
  allow limited retry/refine under a deadline

high-risk / irreversible:
  allow slower verification, stronger model, approval, audit, or human review
```

Fast and safe are not always the same path. The runtime should make the tradeoff
explicit.

Examples:

```text
draft text suggestion
  -> fast model, light validation, editable result

legal statement applied to a report
  -> stricter validation, possible second model, approval

business write action
  -> policy gate, dry-run, approval, audit, fail-closed behavior
```

### Feedforward For Real-Time Work

When a decision must be fast, do not always wait until the decision moment to
call the model.

Use feedforward:

```text
observe likely future demand
prepare candidate plans early
validate candidates before they are needed
cache safe alternatives
at decision time, select from prepared candidates
```

Port-operation example:

```text
vessel ETA is known
-> generate berth allocation candidates before arrival
-> validate candidates against current state
-> at arrival, choose or lightly adjust a prepared plan
```

This shifts slow reasoning earlier and keeps the real-time path short.

### Deadline-Aware Control

Every controlled AI execution should know its time budget.

Possible behavior:

```text
deadline allows 300 ms
  -> deterministic checks only, no model retry

deadline allows 3 seconds
  -> one fast model attempt and deterministic validation

deadline allows 30 seconds
  -> stronger model, refine loop, fallback, approval preparation
```

Useful policy fields:

```text
maxLatencyMillis
maxModelCalls
maxRetryCount
allowFallbackModel
allowHumanEscalation
onDeadlineExceeded
```

Suggested `onDeadlineExceeded` values:

```text
return_cached_safe_result
return_draft_only
defer_and_notify
fail_closed
request_human_review
```

The runtime should not let a feedback loop silently exceed a business deadline.

### Key Rule

```text
Fast loops protect latency.
Slow loops improve future behavior.
LLM calls are expensive interventions, not the default reaction to every error.
```

## Control Scope By Layer

### flower-ai-harness

Owns local AI task reliability:

```text
current output validation
retry/refine
fallback
attempt-level trace
token/cost budget
timeout
schema repair
```

Best fit:

```text
attempt-level immediate correction
schema/format repair
bounded retry/refine for one AI task
```

`flower-ai-harness` should not own long-term business behavior control,
approval escalation, persistent correction memory, or runtime governance.

### flower-agent-runtime

Owns business action control:

```text
action proposal quality
tool/action misuse
policy denial trends
approval rejection trends
business effect safety
audit and trace correlation
worker behavior over many runs
```

Best fit:

```text
business action policy and approval
idempotency / duplicate proposal handling
audit and replay correlation
optional control wrapper selection
```

### flower-agent-runtime-control

Future optional module.

Owns feedback/control mechanics around controlled action execution:

```text
sensor result normalization
typed error signals
severity-based correction decisions
repeated error aggregation
divergence guard / circuit breaker
control events
control profile versioning
```

Best fit:

```text
P-like intervention around a harness or action result
I-like repeated error aggregation across runs
D-like instability / oscillation / runaway-loop detection
```

This module should be extracted only after a host application proves a small
loop. It should not be the first generic runtime implementation.

### Host Application

Owns domain truth:

```text
real validation rules
legal/domain examples
approval UI
domain regression cases
persistent feedback store
prompt release process
final policy decisions
```

Best fit:

```text
durable error memory
domain-specific corrections
prompt/template governance
evaluation set updates
```

## Implementation Boundary

The P/I/D-like feedback model belongs to the runtime stack, but not as one
flat module.

Each part sits at a different depth:

```text
control plane / governance layer
  -> D-like trend control:
     divergence detection, oscillation detection, escalation, safe stop

state / memory layer
  -> I-like accumulated correction:
     repeated error counting, persistent corrections, anti-windup

harness boundary
  -> P-like current correction:
     validate current output, grade severity, retry/refine/fallback now

domain layer
  -> error detection:
     decide what counts as an error in this business domain
```

This means:

```text
P is closest to flower-ai-harness.
I requires runtime state and persistence.
D belongs to the runtime control plane and governance layer.
Domain code defines error signals but should not own the generic controller.
```

More precisely:

```text
flower-ai-harness may perform immediate task-level repair.
flower-agent-runtime-control owns reusable feedback/control policy.
host applications define what an error means.
```

### Mechanism vs Domain

The feedback controller should be a domain-neutral mechanism.

It should understand:

```text
errorCode
severity
attemptNo
actionId
workerId
modelId
promptVersion
schemaVersion
interventionType
accumulatedError
errorTrend
```

It should not understand:

```text
berth allocation rules
legal article meaning
inspection report templates
game moderation rules
port equipment constraints
```

The host application provides domain-specific detectors and validators.

Examples:

```text
ArchDox:
  detects missing legal basis, invalid report section, unsupported claim

Port system:
  detects duplicate berth assignment, unreachable container stack,
  priority inversion

Game operations:
  detects false positive ban, unsafe moderation action, missing evidence
```

Each detector emits the same kind of control signal:

```text
AiControlError(
  errorCode,
  severity,
  evidence,
  actionId,
  workerId,
  modelId,
  promptVersion,
  attemptNo
)
```

The generic controller then decides how to respond.

### Clean Dependency Direction

Recommended dependency direction:

```text
host domain detector
  -> emits AiControlError

flower-ai-harness
  -> uses P-like immediate correction for one AI task

flower-agent-runtime
  -> stores I-like accumulated signals
  -> runs D-like governance checks
  -> owns action-level escalation and safe stop

control dashboard / tuning layer
  -> observes samples and recommends control profile changes
```

Do not let the generic feedback controller import host domain concepts.

Do not let a domain-specific worker bypass the generic control signal model.

### Practical Module Implication

Possible future module split:

```text
flower-ai-harness-core
  - local output validation hooks
  - P-like severity-to-intervention policy
  - attempt-level control events

flower-agent-runtime-core
  - AiControlError
  - ControlEvent
  - ControlProfile
  - error aggregation store candidate
  - divergence guard / circuit-breaker candidate
  - action-level escalation events

flower-agent-runtime-test
  - fake error detector
  - synthetic control events
  - control policy tests

host application
  - domain error detectors
  - domain validators
  - domain correction text
  - domain approval and policy decisions
```

This keeps the reusable control engine extractable.

### Harness Wrapping Rule

Do not put the full P/I/D control engine inside `flower-ai-harness`.

`flower-ai-harness` should remain the reliable AI task execution component:

```text
model call
structured output
schema validation
retry/refine/fallback
timeout
token/cost trace
attempt-level lifecycle
```

`flower-agent-runtime` should apply higher-level control by wrapping harness
execution:

```text
ActionRuntime
-> controlled harness wrapper
   -> calls flower-ai-harness
   -> receives harness result
   -> adapts result into AiControlSignal
   -> asks domain detector / validator for error signal
   -> chooses severity-based intervention
   -> retries, refines, falls back, fails closed, or returns result
```

The harness does not need to know it is wrapped by a control module.

If the harness result does not contain enough information for control, prefer
an adapter first:

```text
HarnessResultAdapter
  harness result -> AiControlSignal
```

Only extend `flower-ai-harness` when generic execution metadata is missing.

Acceptable harness-level additions:

```text
attemptNo
modelId
promptVersion
schemaVersion
validation result
failure reason
token/cost
latency
traceId
raw/structured output access where safe
```

Avoid harness-level additions:

```text
business action policy
domain error classification
approval rules
I-like accumulated memory
D-like runtime governance
control dashboard logic
prompt auto-tuning authority
```

This keeps the harness replaceable. A host application may later use a
different AI executor, LangGraph-style graph, or external service behind an
adapter while keeping the same runtime control layer.

### Key Rule

```text
The framework owns how errors are handled.
The host application owns what counts as an error.
```

That boundary is what keeps the control engine reusable across ArchDox,
port-operation workers, game-operation workers, and other business systems.

## Minimal Data Model

Start small.

Suggested event:

```java
public record AiControlError(
    String errorCode,
    String severity,
    String sensorId,
    String sensorType,
    String source,
    String runId,
    String actionId,
    String modelId,
    String promptVersion,
    String schemaVersion,
    int attemptNo,
    String evidence,
    String suggestedIntervention
) {}
```

Suggested accumulated signal:

```java
public record AiErrorAggregate(
    String actionId,
    String modelId,
    String promptVersion,
    String errorCode,
    String correctionCategory,
    long count,
    double recentRate,
    String firstSeenAt,
    String lastSeenAt
) {}
```

Suggested trend signal:

```java
public record AiErrorTrend(
    String runId,
    int windowSize,
    double severitySlope,
    double retryCostSlope,
    boolean diverging
) {}
```

These are not final APIs. They define the kind of signals the framework should
eventually support.

## Minimal Controller Loop

Conceptual algorithm:

```text
1. Run AI task or agent action.
2. Validate output and collect typed errors.
3. Apply current-error intervention for this run.
4. Append errors to trace/audit/feedback store.
5. Detect repeated errors across recent runs.
6. Recommend persistent correction when repeated bias is confirmed.
7. Detect divergence within the current run.
8. Stop, reset, fallback, or escalate if the run is becoming unstable.
```

Pseudo-code:

```java
AiControlDecision decide(AiControlSignal signal) {
    if (signal.hasCriticalPolicyError()) {
        return failClosed("critical policy error");
    }

    if (signal.trend().diverging()) {
        return escalateOrReset("diverging loop");
    }

    if (signal.currentError().isSchemaRepairable()) {
        return retryWith("schema repair prompt");
    }

    if (signal.aggregate().repeatedBiasDetected()) {
        return recommendPersistentCorrection("prompt/template update");
    }

    return continueRun();
}
```

The controller should make decisions explicit and auditable.

## Recommended Tuning Order

Do not implement P, I, and D all at once.

Recommended order:

```text
1. P-like current error correction
2. I-like accumulated bias correction
3. D-like divergence/oscillation damping
```

Reason:

```text
P creates the basic correction loop.
I learns from repeated bias after P makes errors measurable.
D requires enough history to distinguish real trend from normal noise.
```

In practice:

```text
P:
  improve existing retry/refine by mapping error severity to intervention
  strength.

I:
  track repeated classified errors and recommend persistent corrections.

D:
  detect worsening or oscillating attempts once P and I are producing traces.
```

This mirrors practical control tuning:

```text
stabilize current response
remove repeated steady-state error
dampen overshoot and oscillation
```

The first target should be small enough to validate with real runs. Do not make
the first version a general autonomous self-tuning controller.

## First Experiment

Do not build the whole system at once.

Start with P-like proportional correction if the existing harness retry logic
is still crude.

Then add I-like accumulated error correction.

Example ArchDox experiment:

```text
Scenario:
  Document worker repeatedly generates dates in the wrong format.

Detection:
  validator emits wrong_locale_date_format.

Aggregation:
  same actionId + promptVersion + errorCode appears 3 times in 20 runs.

Intervention:
  create prompt correction recommendation:
    "Use Korean date format: yyyy.MM.dd. Do not use MM/dd/yyyy."

Verification:
  add regression case.
  run old prompt vs corrected prompt.
  compare error rate.
```

This proves whether persistent feedback improves real worker quality.

After that:

```text
add D-like divergence detection within one run
add model routing changes only when evidence is strong
```

## Integration With Flower Flow

The feedback loop can be represented as Flower steps.

Example:

```text
RunHarnessStep
-> ValidateOutputStep
-> ClassifyControlErrorStep
-> DecideInterventionStep
-> RetryRefineFallbackStep
-> RecordControlSignalStep
-> EmitControlAuditStep
```

For agent actions:

```text
ActionProposalStep
-> ValidateProposalStep
-> PolicyGateStep
-> DetectActionBiasStep
-> ApprovalOrExecutionStep
-> RecordAgentFeedbackStep
```

This keeps the controller visible and recoverable.

## What Not To Do

Avoid:

```text
literal PID formula as the first implementation
unbounded auto-prompt rewriting
model self-modifying policy rules
automatic permanent memory updates without review
changing many intervention axes at once
letting trend detection silently suppress audit
using vague critique text as the only error signal
```

Persistent corrections should be reviewed, versioned, and evaluated.

## Good Product Sentence

Possible positioning:

```text
Control-theory-inspired feedback loops for reliable AI workers.
```

More concrete:

```text
Flower does not make the model smarter.
Flower makes model behavior observable, bounded, corrected, and recoverable.
```

This idea should remain grounded in actual validation data. If repeated error
tracking improves ArchDox worker quality, then it can move from concept into
framework design.
