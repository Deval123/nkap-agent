package dev.nkap.agent.approval;

import java.util.UUID;

/** What came of a proposal: Nkap recorded it, or it was refused with a fixed text. */
public sealed interface ProposalResult {

    record Created(UUID id) implements ProposalResult {
    }

    record Refused(String code, String text) implements ProposalResult {
    }
}
