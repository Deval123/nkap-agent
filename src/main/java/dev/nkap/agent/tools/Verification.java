package dev.nkap.agent.tools;

/** The verdict on one {@link EvidenceRef}: accepted, or refused with the reason. */
public record Verification(boolean ok, String reason) {

    static Verification accepted() {
        return new Verification(true, "");
    }

    static Verification refused(String reason) {
        return new Verification(false, reason);
    }
}
