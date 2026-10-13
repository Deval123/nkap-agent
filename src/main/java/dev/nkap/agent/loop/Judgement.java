package dev.nkap.agent.loop;

/** Whether a run did what the catalogue expects of its case, and a short text saying how. */
public record Judgement(boolean matches, String text) {
}
