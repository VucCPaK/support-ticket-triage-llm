package dev.kyrylo.ticket_triage.triage;

import dev.kyrylo.ticket_triage.llm.LlmCallMetrics;

/** What one triage model call produced: the answer and what the call cost. */
record TriageCall(TriageResult result, LlmCallMetrics metrics) {
}
