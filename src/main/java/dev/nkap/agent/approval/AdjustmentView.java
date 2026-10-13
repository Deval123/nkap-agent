package dev.nkap.agent.approval;

import java.util.UUID;

/** The few fields of an adjustment this client shows; anything else the server sent is not carried. */
public record AdjustmentView(UUID id, String status, String hypothesis, String ledgerEntryId) {
}
