package com.solace.schemavalidator.validation;

public record ValidationOutcome(boolean valid, String reason) {

    public static ValidationOutcome success() {
        return new ValidationOutcome(true, null);
    }

    public static ValidationOutcome failure(String reason) {
        return new ValidationOutcome(false, reason);
    }
}
