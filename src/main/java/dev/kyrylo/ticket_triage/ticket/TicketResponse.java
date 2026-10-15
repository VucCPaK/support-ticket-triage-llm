package dev.kyrylo.ticket_triage.ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        String customerEmail,
        String subject,
        String body,
        TicketStatus status,
        Instant createdAt
) {

    static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getCustomerEmail(),
                ticket.getSubject(),
                ticket.getBody(),
                ticket.getStatus(),
                ticket.getCreatedAt()
        );
    }
}
