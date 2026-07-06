package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.AuditEventType;
import io.github.parkkevinsb.flower.agent.runtime.ValidationResult;
import io.github.parkkevinsb.flower.core.step.Step;
import io.github.parkkevinsb.flower.core.step.StepContext;
import io.github.parkkevinsb.flower.core.step.StepResult;

import java.util.Map;

final class ValidateInputStep extends Step {
    private final FlowActionExecutionSession session;

    ValidateInputStep(FlowActionExecutionSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        ValidationResult validation = session.inputValidator()
                .validate(session.proposal(), session.definition(), session.context());
        session.validationResult(validation);
        session.record(AuditEventType.VALIDATION_COMPLETED, Map.of("valid", validation.valid()));
        if (!validation.valid()) {
            ActionExecutionResult result = ActionExecutionResult.validationFailed(
                    String.join("; ", validation.violations()));
            session.result(result);
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StepResult.goTo("record-result");
        }
        return StepResult.done();
    }
}
