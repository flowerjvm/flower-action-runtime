package io.github.flowerjvm.flower.action.runtime.audit;

public interface TraceSink {
    void record(AuditEvent event);

    static TraceSink noop() {
        return event -> {
        };
    }
}
