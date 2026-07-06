package io.github.parkkevinsb.flower.agent.runtime;

public interface TraceSink {
    void record(AuditEvent event);

    static TraceSink noop() {
        return event -> {
        };
    }
}
