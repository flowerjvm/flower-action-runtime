package io.github.parkkevinsb.flower.agent.runtime.audit;

public interface AuditSink {
    void record(AuditEvent event);

    static AuditSink noop() {
        return event -> {
        };
    }
}
