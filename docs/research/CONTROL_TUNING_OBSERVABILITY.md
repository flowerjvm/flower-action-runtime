# Control Tuning And Observability

This document defines how Flower should observe, tune, and operate
control-theory-inspired LLM feedback loops.

This is not a `flower-core` responsibility and not a reason to make
`flower-ai-harness` own business governance. Treat this as the future operating
model for an optional `flower-agent-runtime-control` layer after host
applications prove small feedback loops in real work.

The key idea:

```text
You cannot tune what you cannot see.
```

Before automatic tuning, the framework needs data, graphs, distributions,
traceable control decisions, and a human-operable tuning workflow.

## Core Decision

The safe industrial direction is:

```text
manual tuning + control dashboard first
assisted auto-tuning second
continuous adaptive control only as a research feature, not production default
```

In other words:

```text
Observe first.
Recommend second.
Apply only through explicit human or deterministic policy control.
```

Do not let an LLM freely change controller gains, retry budgets, approval
rules, or policy parameters in production.

## Why Observability Comes First

P/I/D-like LLM control is only useful if the operator can see what is happening.

For each worker/action, the system should show:

```text
current error rate
error severity over attempts
retry/refine count
intervention strength
I-like accumulated correction count
windup/saturation risk
D-like divergence trend
oscillation patterns
cost and latency
policy denial rate
approval rejection rate
final success/failure rate
```

This is the LLM worker equivalent of looking at a response curve on an
oscilloscope.

The dashboard is not decoration. It is the tuning instrument.

## Control Values Worth Recording

The runtime should record enough data to tune later.

Do not start by designing a large `AiControlSample` API with `pGain`, `iGain`,
`dGain`, creativity scores, optimization scores, and actuator math. That makes
the metaphor look more proven than it is.

Start with small event data:

```text
run id / trace id
action id / worker id
model id / prompt version / schema version
attempt number
error code
sensor id
severity
intervention type
retry/refine/fallback count
final state
cost and latency
```

If later experiments prove that gain-like parameters are useful, they can be
added as versioned control profile data. They should not be part of the first
public API.

## First Control Experiment

The first experiment should be deliberately narrow.

Example:

```text
Sensor:
  wrong_locale_date_format

Observation:
  count occurrences per action/model/prompt version.

I-like rule:
  if wrong_locale_date_format appears 3 times in 20 runs,
  add or suggest a persistent date-format correction for that action profile.

Validation:
  rerun the same small scenario set and compare before/after failure count.
```

This proves whether accumulated correction is useful without building a broad
control framework too early.

## Useful Visualizations

The console or host UI should eventually show distributions, not only single
values.

Useful charts:

```text
error severity over attempt number
retry count distribution per action
latency distribution per model/action
cost distribution per action/prompt version
errorCode histogram
policy denial histogram
approval rejection histogram
intervention strength over time
I accumulation per errorCode
active correction count per action
D slope over attempts
oscillation count over recent runs
success rate by prompt version
fallback rate by model
actuator change frequency
temperature vs schema failure rate
temperature vs optimization score
prompt constraint count vs success rate
prompt constraint count vs latency/cost
schema strictness vs retry count
model choice vs cost/quality/latency
```

Useful comparisons:

```text
before tuning vs after tuning
promptVersion A vs promptVersion B
model A vs model B
normal scenario vs stress scenario
manual setting vs recommended setting
actuator setting A vs actuator setting B
```

Useful distribution views:

```text
histogram
percentiles
box plot
scatter plot
rolling average
control-limit chart
```

Do not hide everything behind one "AI quality score". A single score can be
useful for summaries, but tuning needs the component signals.

## Coupling Observability

The dashboard should help detect when one control input affects multiple output
qualities.

Examples:

```text
temperature lowered
  -> schema failures decreased
  -> optimization score also decreased

prompt constraints increased
  -> safety score increased
  -> latency and retry count increased

schema strictness increased
  -> invalid outputs decreased
  -> retry cost increased
```

This is coupling data.

Record:

```text
which actuator changed
old value
new value
who/what changed it
why it changed
which output metrics moved after the change
```

Useful coupling views:

```text
actuator change timeline
scatter plot: temperature vs consistency score
scatter plot: temperature vs optimization score
scatter plot: prompt constraint count vs retry count
before/after distribution by control profile version
correlation heatmap for actuator values and output metrics
```

Do not let two controllers silently fight over the same actuator. If multiple
objectives want the same actuator, show it.

Useful warning:

```text
temperature was lowered by consistency policy and raised by creativity policy
within the same run window
```

This is a controller conflict signal.

## Tuning Parameters

The first tuneable values should be explicit and boring.

Examples:

```text
severity thresholds
retry limit
refine limit
intervention strength by severity
fallback threshold
P correction scope
I accumulation threshold
I observation window
I max active corrections
I correction expiration
D rolling window size
D divergence threshold
D oscillation threshold
cost budget
latency budget
approval escalation threshold
temperature allowed range by risk level
prompt constraint limit
actuator ownership rule
controller conflict policy
```

These values should be versioned.

Suggested configuration identity:

```text
controlProfileId
controlProfileVersion
actionId
workerId
promptVersion
modelId
modelVersion
effectiveFrom
createdBy
changeReason
```

Every production run should record which control profile was used.

## Model Change Handling

Model changes do not erase all feedback knowledge.

Separate stored feedback into:

```text
domain correction
  = model-independent knowledge.
  = keep across model changes.

model behavior correction
  = model-specific habit or weakness.
  = reset, quarantine, or revalidate when model changes.

control gains / thresholds
  = model-dependent tuning values.
  = rerun tuning session after model changes.
```

P/I/D model-change impact:

```text
P:
  no long-term accumulated store.
  revalidate intervention strength.

D:
  only recent trend window.
  clear window or let old samples age out.
  revalidate divergence/oscillation thresholds.

I:
  durable accumulated store.
  keep domain corrections.
  reset/quarantine model behavior corrections.
```

Recommended model-change workflow:

```text
1. Register new modelId/modelVersion.
2. Keep domain correction store active.
3. Disable old model behavior corrections for the new model.
4. Clone baseline control profile.
5. Run assisted tuning/evaluation scenarios for the new model.
6. Publish a new controlProfileVersion for the new model.
7. Start accumulating new model behavior corrections.
```

The dashboard should show which corrections are:

```text
domain-level
model-specific
active for this model
inactive because model changed
pending revalidation
```

## Manual Tuning Workflow

The first safe workflow:

```text
1. Select action/worker to inspect.
2. View recent runs, errors, distributions, cost, latency, and trend.
3. Adjust tuning values manually.
4. Run evaluation scenarios.
5. Compare before/after metrics.
6. Publish a new control profile version.
7. Monitor for regression or retuning signal.
```

This keeps control in human hands.

The UI can provide sliders and inputs, but the important part is not the
sliders. The important part is that every change is:

```text
visible
versioned
audited
rollbackable
evaluated
```

## Assisted Auto-Tuning Workflow

The safer version of "automatic tuning" is not continuous self-adaptation.

It is a bounded tuning session.

Recommended workflow:

```text
1. Operator clicks Start Tuning.
2. Runtime creates TuningSession.
3. System runs real or scenario-based workloads.
4. Control telemetry accumulates.
5. Recommendation engine computes candidate values.
6. Dashboard shows convergence and confidence.
7. Operator clicks End Tuning.
8. System proposes a new control profile.
9. Operator reviews and publishes or rejects it.
10. Production runs with fixed values.
```

The automatic part happens during the tuning session. Production should run
with fixed, versioned values unless a human or deterministic safety policy
changes them.

## Tuning Session Model

Suggested model:

```java
public record TuningSession(
    String tuningSessionId,
    String actionId,
    String workerId,
    String baselineControlProfileVersion,
    String mode,
    String startedAt,
    String endedAt,
    String startedBy,
    String status,
    long sampleCount,
    String scenarioCoverageSummary,
    boolean recommendationConverged,
    String recommendationSummary,
    String proposedControlProfileVersion
) {}
```

Suggested mode values:

```text
manual
assisted_auto_tuning
offline_evaluation
stress_scenario_tuning
```

## When Is Enough Data Enough?

Do not end tuning only because a fixed number of runs completed.

Better signals:

```text
recommendation values are converging
error distribution is stable
scenario coverage is sufficient
stress scenarios were included
confidence interval is acceptable
cost/latency distribution is understood
no critical safety failures are unresolved
```

Useful dashboard signals:

```text
recommended P/I/D-like parameters over time
recommended retry/fallback thresholds over time
error histogram stabilization
success rate rolling average
divergence/oscillation rolling rate
sample coverage by scenario type
```

If recommended values keep moving, tuning is not finished.

## Scenario Coverage

LLM tuning data must represent real operating conditions.

Bad tuning set:

```text
only easy requests
only normal traffic
only successful runs
only one model
only one prompt version
```

Good tuning set:

```text
normal cases
edge cases
stress cases
invalid input cases
policy denial cases
approval rejection cases
tool timeout cases
high-cost cases
high-latency cases
known historical failure cases
```

For industrial systems, include pressure scenarios intentionally.

Example:

```text
Do not tune a port-planning worker only on quiet traffic.
Include peak traffic, conflicting berth assignments, delayed equipment,
unreachable container positions, and priority conflicts.
```

## Retuning Signal In Production

Production should not continuously mutate gains by default.

But production should detect when retuning is needed.

Retuning signals:

```text
error rate exceeds control limit
oscillation rate increases
divergence rate increases
policy denial pattern changes
approval rejection rate changes
fallback rate increases
cost distribution shifts
latency distribution shifts
new errorCode appears frequently
prompt/model version changes
scenario distribution changes
```

Recommended behavior:

```text
raise retuning-needed event
switch to conservative safety profile if needed
notify operator
open tuning session
do not silently let LLM rewrite control values
```

## Auto-Tuning vs Adaptive Control

Important distinction:

```text
Auto-tuning:
  tuning mode starts
  data is collected
  recommended values are produced
  operator accepts or rejects
  production uses fixed values

Adaptive control:
  production continuously adjusts control values while running
```

For Flower's industrial identity:

```text
auto-tuning is acceptable
adaptive control must be heavily restricted
LLM-controlled adaptive tuning is not a production default
```

If adaptive behavior is ever added, it should be:

```text
deterministic
bounded
audited
rollbackable
limited to safe ranges
disabled for high-risk actions by default
```

An LLM may summarize tuning data or suggest hypotheses, but it should not be
the authority that directly writes production control parameters.

## Fine-Tuning And Prompt Tuning

The same data can later support:

```text
prompt tuning
control-profile tuning
model routing changes
eval/regression set updates
fine-tuning dataset selection
human review process improvement
```

But do not confuse them.

```text
control tuning
  = how strongly the runtime intervenes, retries, falls back, approves, or
    stops.

prompt tuning
  = how instructions/context are changed.

fine-tuning
  = how model behavior is trained outside the runtime.
```

Even if fine-tuning improves the model, the runtime still needs validation,
policy, approval, trace, and recovery. Fine-tuning does not replace control.

## Relationship To Flower Console

This does not have to be implemented as a large product first.

Start with data and small views.

Possible progression:

```text
Phase 1:
  emit AiControlSample events and store them.

Phase 2:
  expose simple tables and JSON summaries.

Phase 3:
  add histograms and rolling charts for one action.

Phase 4:
  add manual control profile editing and before/after comparison.

Phase 5:
  add bounded tuning sessions and recommendations.
```

The important rule:

```text
Do not build the dashboard before the data exists.
Do not auto-tune before the dashboard can explain the recommendation.
```

## Minimal First Version

The smallest useful version:

```text
AiControlSample event
controlProfileVersion on every run
errorCode histogram
retry/refine count distribution
cost/latency distribution
I accumulated error counters
D divergence/oscillation flags
manual tuning notes
before/after comparison for one action
```

This is enough to start tuning with evidence instead of intuition.

## Product Sentence

Possible positioning:

```text
Flower gives AI workers a control dashboard, not just a chat log.
```

More concrete:

```text
Flower records the signals needed to tune AI worker behavior like an
industrial control loop: visible, bounded, versioned, and auditable.
```
