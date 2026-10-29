package dev.kyrylo.ticket_triage.triage;

import dev.kyrylo.ticket_triage.llm.LlmCallMetrics;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ticket_triage")
@Getter
public class TicketTriage {
    @Id
    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "order_number")
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketPriority priority;

    @Column(nullable = false)
    private boolean escalate;

    @Column(name = "escalate_reason")
    private String escalateReason;

    @Column(nullable = false)
    private String summary;

    @Column(nullable = false)
    private String model;

    @Column(name = "usd_cost", nullable = false, precision = 12, scale = 8)
    private BigDecimal usdCost;

    @Column(name = "prompt_version", nullable = false)
    private Integer promptVersion;

    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens;

    @Column(name = "reasoning_tokens")
    private Integer reasoningTokens;

    @Column(name = "latency_ms", nullable = false)
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketTriage() {
        // for JPA
    }

    private TicketTriage(UUID ticketId, TriageResult result, LlmCallMetrics metrics, int promptVersion, Instant createdAt) {
        this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
        // The model can return incomplete JSON: fail here with a clear message instead of a DB constraint error
        this.category = Objects.requireNonNull(result.category(), "category");
        this.priority = Objects.requireNonNull(result.priority(), "priority");
        this.summary = Objects.requireNonNull(blankToNull(result.summary()), "summary");
        this.orderNumber = blankToNull(result.orderNumber());
        this.escalate = result.escalate();
        // Matches CHECK (escalate OR escalate_reason IS NULL): drop a reason the model gave without escalating
        this.escalateReason = result.escalate() ? blankToNull(result.escalateReason()) : null;

        this.model = metrics.model();
        this.usdCost = metrics.costUsd();
        this.inputTokens = metrics.inputTokens();
        this.outputTokens = metrics.outputTokens();
        this.reasoningTokens = metrics.reasoningTokens();
        this.latencyMs = metrics.latencyMs();
        this.promptVersion = promptVersion;
        this.createdAt = createdAt;
    }

    public static TicketTriage of(UUID ticketId, TriageResult result, LlmCallMetrics metrics, int promptVersion, Instant now) {
        return new TicketTriage(ticketId, result, metrics, promptVersion, now);
    }

    // Models often return "" instead of null for missing values
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
