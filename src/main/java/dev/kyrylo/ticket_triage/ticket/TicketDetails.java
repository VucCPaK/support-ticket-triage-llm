package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.triage.TicketTriage;

public record TicketDetails(
    Ticket ticket,
    TicketTriage triage
) {

    static TicketDetails from(Ticket ticket, TicketTriage triage) {
        return new TicketDetails(ticket, triage);
    }
}
