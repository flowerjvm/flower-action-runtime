package io.github.parkkevinsb.flower.agent.runtime.validation;


import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionDefinition;
public interface ActionInputValidator {
    ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context);

    static ActionInputValidator allowAll() {
        return (proposal, definition, context) -> ValidationResult.ok();
    }
}
