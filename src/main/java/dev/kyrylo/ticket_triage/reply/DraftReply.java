package dev.kyrylo.ticket_triage.reply;

import dev.kyrylo.ticket_triage.triage.LlmCallMetrics;

public record DraftReply (
    String text,
    String orderContext,  // the order block exactly as the model saw it; the order itself changes over time
    LlmCallMetrics metrics,
    int toolCalls
) { }
