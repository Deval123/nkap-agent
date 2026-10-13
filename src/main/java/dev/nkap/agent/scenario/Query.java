package dev.nkap.agent.scenario;

/**
 * One answer to a status query. {@code reason} is empty when the answer carries none. The last query
 * of a behaviour is the one the simulator repeats.
 */
public record Query(String status, String reason) {

    public Query {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("a query answer needs a status");
        }
        reason = reason == null ? "" : reason;
    }
}
