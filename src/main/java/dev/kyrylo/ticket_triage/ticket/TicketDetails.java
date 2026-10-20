package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.reply.TicketDraftReply;
import dev.kyrylo.ticket_triage.triage.TicketTriage;

public record TicketDetails(
    Ticket ticket,
    TicketTriage triage,           // null when triage failed
    TicketDraftReply draftReply    // null when there is no triage or the draft failed
) { }
