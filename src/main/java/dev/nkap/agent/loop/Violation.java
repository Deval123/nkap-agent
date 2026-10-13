package dev.nkap.agent.loop;

/** A rule a final answer broke: a fixed code and a fixed text, never the model's words. */
public record Violation(String code, String text) {
}
