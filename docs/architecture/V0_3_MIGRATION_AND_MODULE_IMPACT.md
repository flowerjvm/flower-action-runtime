# 0.3 Migration And Module Impact

This note describes the breaking cleanup in the `0.3.0` release and how a
host application migrates from `0.2.x`.

## Why 0.3 Is Breaking

The 0.2 line introduced explicit request-channel and proposer identities,
asynchronous/deferred execution, durable Run state, and context-aware duplicate
handling. It retained compatibility shapes that allowed contradictory identity,
fake executor methods, and duplicate-result lookup before authorization. The
0.3 line removes those shapes while the project is still pre-1.0.

## Identity Contract

`ActionOrigin` and the `origin` fields on `ActionProposal` and `ActionRun` are
removed. Use all three remaining identity dimensions deliberately:

```text
requestChannel   where the request entered
proposerType     what kind of actor suggested it
ExecutionContext trusted tenant and execution principal
```

`ActionDefinition` now declares `allowedRequestChannels` and
`allowedProposerTypes` separately. Neither proposal field grants authority;
`PolicyGate` must authorize from trusted context and current resource facts.

Recommended host migration:

1. centralize proposal creation behind a host factory or governed-action
   service;
2. remove every `.origin(...)` call and `ActionOrigin` import;
3. map old UI/API/CLI values to `ActionRequestChannel`;
4. map old USER/AI_PLANNER/SYSTEM values to `ActionProposerType`;
5. update action definitions to allow channels and proposers independently.

## Executor Contracts

`ActionExecutor` is now the common dispatch contract. Implement exactly one
mode-specific interface:

| Work model | 0.3 contract |
| --- | --- |
| Short, bounded, inline work | `SynchronousActionExecutor` |
| In-process asynchronous work | `AsyncActionExecutor` |
| External queue, worker, remote job, or callback | `DeferredActionExecutor` |

Existing synchronous implementations change `implements ActionExecutor` to
`implements SynchronousActionExecutor`; their `execute(...)` body does not
change. Async and deferred implementations no longer inherit an unusable
`execute(...)` method that throws `UnsupportedOperationException`.

## Pipeline And Duplicate Security

The initial pipeline order is now:

```text
record-proposal
-> resolve-action
-> validate-input
-> evaluate-policy
-> reserve-duplicate
-> request-approval
-> pre-execution-check
-> execute-action
-> record-result
```

This means an unregistered, invalid, or unauthorized request cannot receive a
previously completed result through `DuplicateActionPolicy.RETURN_EXISTING`.
Approval requests happen after the duplicate reservation so repeated approval
requests still collapse to one logical operation. Approval resume keeps the
original reservation and re-resolves, re-validates, re-evaluates policy, and
runs the pre-execution guard before dispatch.

`DuplicateActionPolicy` now exposes only context-aware `reserve`, `complete`,
and `release` methods. Update custom implementations to accept
`ExecutionContext` for all three operations. Durable uniqueness must still be
scoped by at least:

```text
tenantId + actionId + idempotencyKey
```

Add principal or resource-visibility scope when authorized users within a
tenant cannot safely share the existing result.

## JDBC Migration

Fresh 0.3 schemas no longer contain `action_run.origin`. Existing 0.2 schemas
must apply the matching additive migration resource:

```text
db/action_run/migration/h2-0.2-to-0.3.sql
db/action_run/migration/mysql-0.2-to-0.3.sql
db/action_run/migration/postgresql-0.2-to-0.3.sql
```

Integrate the appropriate statement into the host's Flyway/Liquibase sequence;
do not edit an already-applied migration. Deploy application code and schema in
the order required by the host's rolling-deployment policy because 0.2 code
still expects the legacy column while 0.3 no longer reads or writes it.

## Module Impact

- `core`: breaking source contract changes described above; policy precedes
  duplicate lookup.
- `persistence-jdbc`: schema, binding, and mapping drop `origin`; CAS behavior
  is unchanged.
- `workflow`: drives the new shared stage order; behavioral parity with core is
  still required.
- `eventloop`: uses the same core executor dispatch and Run transitions;
  approval/recovery behavior is unchanged.
- `integration-test`: verifies JDBC-backed event-loop recovery against the new
  contracts.

No host must adopt workflow or event-loop merely to use 0.3. Core plus JDBC is
still the recommended minimal durable integration.

## Verification

After migration, run a clean full-reactor build and host tests. Include cases
for:

- denied callers reusing the same tenant/action/idempotency key as a completed
  authorized request without receiving its output;
- separate tenant and action scopes for identical idempotency keys;
- synchronous, async, and deferred executor dispatch;
- fresh schema plus the 0.2-to-0.3 database migration;
- direct/workflow parity and JDBC/event-loop recovery;
- real multi-thread and multi-connection terminal CAS races.
