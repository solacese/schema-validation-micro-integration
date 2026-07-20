package com.solace.schemavalidator.validation;

public interface CompiledValidator {

    ValidationOutcome validate(byte[] payload);
}
