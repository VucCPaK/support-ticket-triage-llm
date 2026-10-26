package dev.kyrylo.ticket_triage.triage;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record TriageResult(
        @JsonPropertyDescription("Ticket category for the customer's main request, decided by the Categories rules")
        TicketCategory category,
        @JsonPropertyDescription("Handling priority, decided by the Priority rules: impact on the customer and how fast it gets worse")
        TicketPriority priority,
        @JsonPropertyDescription("Order number like A123, exactly as written in the ticket. Null if the ticket has no order number")
        String orderNumber,
        @JsonPropertyDescription("True only when the Escalation rules apply")
        boolean escalate,
        @JsonPropertyDescription("Short reason why the ticket is escalated. Null when escalate is false")
        String escalateReason,
        @JsonPropertyDescription("One short sentence in English describing what the customer wants")
        String summary
) {
    public TriageResult withPriority(TicketPriority newPriority) {
        return new TriageResult(category, newPriority, orderNumber, escalate, escalateReason, summary);
    }
}
