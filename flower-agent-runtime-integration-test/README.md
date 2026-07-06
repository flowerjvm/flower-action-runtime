# flower-agent-runtime-integration-test

Cross-module integration tests for `flower-agent-runtime`.

This module verifies behavior that belongs to the combination of modules rather
than to one module alone. For example, it can prove that:

- `JdbcRunStore` persists a waiting `ActionRun`,
- a new `EventLoopActionRuntime` can re-register that run after restart, and
- an approval signal resumes and completes the action through the normal runtime pipeline.
