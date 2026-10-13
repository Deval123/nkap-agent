package dev.nkap.agent.tools;

import java.util.Optional;
import java.util.UUID;

/**
 * Where the statement files the operator uploaded are kept, by the id of the import the gateway made of them.
 * The only way in is a UUID: the model never gives a path.
 */
public interface StatementStore {

    /**
     * The text of the statement file for this import, or empty if there is none (or nothing readable under that
     * name). Throws {@link StatementRefusedException} for a file that exists but is too large to be read.
     */
    Optional<String> find(UUID importId);
}
