package dev.nkap.agent.tools;

/**
 * What the model writes when it cites evidence: the call and a JSON Pointer into that call's fields, and no
 * value. The value is read from the trace ({@link Trace#resolve}) by the harness, which turns the pointer
 * into an {@link EvidenceRef}; the model's own rendering of a value is never what is checked.
 */
public record EvidencePointer(String callId, String path) {
}
