# flower-agent-runtime-eventloop

Event-driven approval-wait backend for `flower-agent-runtime-core`.

This module does not define new governance semantics. It delegates action
execution to `DefaultActionRuntime` and uses Flower EventLoop only to park a
`WAITING_APPROVAL` run until either:

- an approval/rejection signal arrives, or
- the approval deadline expires.

`ActionRun` remains the source of truth. Event flows are transient and are
re-created from `RunStore.findResumable(...)` after restart.

This backend requires a real `RunStore`. A no-op store is not sufficient because
approval waits are restored from persisted `ActionRun` records.

The cross-module JDBC recovery path is covered in
`flower-agent-runtime-integration-test`.

Long-running AI/tool execution is intentionally not moved off the worker thread
in this module. That belongs to a later async execution backend.
