package dev.kyrylo.ticket_triage.ticket;

import java.util.UUID;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(UUID id) {
        super("Ticket " + id + " not found");
    }
}
