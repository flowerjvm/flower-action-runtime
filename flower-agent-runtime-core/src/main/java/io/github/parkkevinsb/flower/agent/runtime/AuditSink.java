package io.github.parkkevinsb.flower.agent.runtime;

public interface AuditSink {
    void record(AuditEvent event);

    static AuditSink noop() {
        return event -> {
        };
    }
}
