package dev.nkap.agent.model;

/**
 * What a call to the model came to: an answer with the tokens it cost, or a failure with the reason. A
 * failure is a value the loop reads, never an exception thrown at it.
 */
public sealed interface ModelResult {

    /** Why a call produced no answer. */
    enum Reason {
        /** No key was available, so nothing was sent. */
        NO_KEY,
        /** The call itself was malformed (no messages), so nothing was sent. */
        INVALID_REQUEST,
        TIMEOUT,
        NETWORK,
        /** The provider answered with a status other than 200. */
        HTTP_STATUS,
        /** The response was larger than the cap, and was not read past it. */
        TOO_LARGE,
        NOT_JSON,
        /** JSON, but not a chat completion in the shape this client reads. */
        MALFORMED_RESPONSE,
        REFUSAL,
        /** The model was cut off (finish reason {@code length}); what it wrote is not an answer. */
        TRUNCATED,
        /** The model finished and wrote nothing. */
        EMPTY,
        /** The answer does not match the schema. */
        SCHEMA,
        /** The usage is missing or not a count; the spend cannot be trusted. */
        BAD_USAGE
    }

    record Answered(ModelAnswer answer, Usage usage) implements ModelResult {
    }

    /**
     * No answer. {@code detail} names the problem and never repeats the prompt, the model's text or the
     * provider's error body. {@code usage} is what the provider reported, if it reported something valid:
     * a call that failed after the model ran still cost tokens.
     */
    record Failed(Reason reason, String detail, Usage usage) implements ModelResult {
    }
}
