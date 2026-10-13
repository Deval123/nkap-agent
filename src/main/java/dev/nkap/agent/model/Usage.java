package dev.nkap.agent.model;

/**
 * Tokens the provider says a call used. Completion tokens include the model's reasoning, which is billed
 * even though its text is never read.
 */
public record Usage(long promptTokens, long completionTokens) {

    public Usage {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("a token count is not negative");
        }
    }
}
