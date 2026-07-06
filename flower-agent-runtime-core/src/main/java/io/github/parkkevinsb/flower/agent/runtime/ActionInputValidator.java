package io.github.parkkevinsb.flower.agent.runtime;

public interface ActionInputValidator {
    ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context);

    static ActionInputValidator allowAll() {
        return (proposal, definition, context) -> ValidationResult.ok();
    }
}
