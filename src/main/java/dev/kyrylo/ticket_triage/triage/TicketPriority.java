package dev.kyrylo.ticket_triage.triage;

public enum TicketPriority {
    URGENT, HIGH, MEDIUM, LOW;

    /** One level more urgent; URGENT stays URGENT. Relies on the declaration order above. */
    public TicketPriority raise() {
        return this == URGENT ? URGENT : values()[ordinal() - 1];
    }
}
