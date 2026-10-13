package dev.nkap.agent.model;

/**
 * One message of the conversation. A tool result is a message of its own; the loop wraps it in a clearly
 * marked block of untrusted data before it gets here, since this type only carries it.
 */
public record Message(Role role, String content) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        /** What a tool returned. Sent to the provider as a user message: it is data, not the model's own words. */
        TOOL_RESULT
    }

    public Message {
        if (role == null || content == null) {
            throw new IllegalArgumentException("a message has a role and a content");
        }
    }
}
