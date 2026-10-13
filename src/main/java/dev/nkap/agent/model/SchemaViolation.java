package dev.nkap.agent.model;

/**
 * An answer that does not match the schema. The message names the field that is wrong and never repeats what
 * the model wrote there, so it is safe to log.
 */
final class SchemaViolation extends Exception {

    SchemaViolation(String message) {
        super(message);
    }
}
