package dev.nkap.agent.scenario;

/** A payment to create on the gateway, and what the simulator does about it. */
public record Payment(String label, long amountMinor, String currency, String msisdn, SimulatorBehaviour behaviour) {

    public Payment {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("a payment amount is a positive number of minor units");
        }
        if (msisdn == null || !msisdn.matches("\\d{8,15}")) {
            throw new IllegalArgumentException("an MSISDN is 8 to 15 digits");
        }
    }

    /** Derived from what the simulator is told to do, so the two cannot disagree. */
    public Ending ending() {
        return switch (behaviour.submit()) {
            case NO_RESPONSE -> Ending.ESCALATED;
            case BAD_REQUEST -> Ending.FAILED;
            case ACCEPT -> behaviour.queries().get(behaviour.queries().size() - 1).status().equals("SUCCESSFUL")
                    ? Ending.SETTLED : Ending.ESCALATED;
        };
    }
}
