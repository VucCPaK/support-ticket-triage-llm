package dev.kyrylo.ticket_triage.reply;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ticket_draft_reply")
@Getter
public class TicketDraftReply {
    @Id
    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "order_context", nullable = false)
    private String orderContext;

    @Column(name = "reply_text", nullable = false)
    private String replyText;

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

    @Column(name = "tool_calls", nullable = false)
    private Integer toolCalls;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketDraftReply() {
        // for JPA
    }

    private TicketDraftReply(UUID ticketId, DraftReply reply, int promptVersion, Instant createdAt) {
        this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
        this.orderContext = Objects.requireNonNull(reply.orderContext(), "orderContext");
        // An empty answer is a failed draft, not a draft to show to an agent
        if (reply.text() == null || reply.text().isBlank()) {
            throw new IllegalArgumentException("Draft reply text is empty");
        }
        this.replyText = reply.text().trim();

        this.model = reply.metrics().model();
        this.usdCost = reply.metrics().costUsd();
        this.inputTokens = reply.metrics().inputTokens();
        this.outputTokens = reply.metrics().outputTokens();
        this.reasoningTokens = reply.metrics().reasoningTokens();
        this.latencyMs = reply.metrics().latencyMs();
        this.toolCalls = reply.toolCalls();
        this.promptVersion = promptVersion;
        this.createdAt = createdAt;
    }

    public static TicketDraftReply of(UUID ticketId, DraftReply reply, int promptVersion, Instant now) {
        return new TicketDraftReply(ticketId, reply, promptVersion, now);
    }
}
