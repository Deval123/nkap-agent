package dev.nkap.agent.tools;

/** The gateway did not answer. The message names the kind of failure and nothing from the request. */
public final class GatewayUnavailableException extends Exception {

    GatewayUnavailableException(String message) {
        super(message);
    }
}
