package dev.nkap.agent.scenario;

import java.util.List;

/**
 * What the simulator does for one payment: how it answers the submission, and the successive answers to
 * status queries. {@code submitCode} is the operator code of a refused submission, empty when there is none.
 */
public record SimulatorBehaviour(String scenarioName, SubmitOutcome submit, String submitCode, List<Query> queries) {

    public SimulatorBehaviour {
        if (scenarioName == null || !scenarioName.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("a scenario name is lower-case words joined by hyphens: " + scenarioName);
        }
        submitCode = submitCode == null ? "" : submitCode;
        if (!submitCode.isEmpty() && submit != SubmitOutcome.BAD_REQUEST) {
            throw new IllegalArgumentException("only a refused submission carries a code");
        }
        queries = List.copyOf(queries);
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("a behaviour needs at least one query answer");
        }
    }
}
