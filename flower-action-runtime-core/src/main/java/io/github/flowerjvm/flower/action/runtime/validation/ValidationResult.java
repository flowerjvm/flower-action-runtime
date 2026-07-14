package io.github.flowerjvm.flower.action.runtime.validation;

import java.util.List;

public record ValidationResult(boolean valid, List<String> violations) {
    public ValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult invalid(String violation) {
        return new ValidationResult(false, List.of(violation == null ? "invalid" : violation));
    }
}
