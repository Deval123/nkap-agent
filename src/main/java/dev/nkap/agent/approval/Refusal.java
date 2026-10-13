package dev.nkap.agent.approval;

/** Why Nkap, or this client, did not do what was asked: a code, and a fixed text a page can show. */
public record Refusal(String code, String text) {
}
