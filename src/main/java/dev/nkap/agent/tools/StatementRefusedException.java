package dev.nkap.agent.tools;

/** A statement file exists but is not read: it is larger than the store allows. The message is fixed text. */
public final class StatementRefusedException extends RuntimeException {

    public StatementRefusedException(String message) {
        super(message);
    }
}
