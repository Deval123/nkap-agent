package dev.nkap.agent.tools;

/**
 * A claim's pointer to evidence: the call that returned it, a JSON Pointer to a field of that result, and
 * the value the claim says the field holds. It is only a claim until {@link Trace#verify} accepts it.
 */
public record EvidenceRef(String callId, String path, String value) {
}
