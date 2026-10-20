package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.triage.TicketCategory;
import dev.kyrylo.ticket_triage.triage.TicketPriority;
import dev.kyrylo.ticket_triage.triage.TicketTriage;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        String customerEmail,
        String subject,
        String body,
        TicketStatus status,
        Instant createdAt,
        TriageResponse triage,
        String draftReply
) {

    public record TriageResponse(
            TicketCategory category,
            TicketPriority priority,
            boolean escalate,
            String escalateReason,
            String orderNumber,
            String summary
    ) {}

    static TriageResponse from(TicketTriage triage) {
        return new TriageResponse(
                triage.getCategory(),
                triage.getPriority(),
                triage.isEscalate(),
                triage.getEscalateReason(),
                triage.getOrderNumber(),
                triage.getSummary()
        );
    }

    static TicketResponse from(TicketDetails details) {
        return new TicketResponse(
                details.ticket().getId(),
                details.ticket().getCustomerEmail(),
                details.ticket().getSubject(),
                details.ticket().getBody(),
                details.ticket().getStatus(),
                details.ticket().getCreatedAt(),
                details.triage() == null ? null : from(details.triage()),
                details.draftReply() == null ? null : details.draftReply().getReplyText()
        );
    }
}

