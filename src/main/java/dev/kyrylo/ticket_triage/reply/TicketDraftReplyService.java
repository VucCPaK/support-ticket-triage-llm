package dev.kyrylo.ticket_triage.reply;

import dev.kyrylo.ticket_triage.common.CostCalculator;
import dev.kyrylo.ticket_triage.config.LlmProperties;
import dev.kyrylo.ticket_triage.order.OrderService;
import dev.kyrylo.ticket_triage.order.OrderSummary;
import dev.kyrylo.ticket_triage.ticket.Ticket;
import dev.kyrylo.ticket_triage.ticket.TicketDetails;
import dev.kyrylo.ticket_triage.triage.LlmCallMetrics;
import dev.kyrylo.ticket_triage.triage.TicketTriage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

@Service
public class TicketDraftReplyService {
    private final ChatClient chatClient;
    private final OrderService orderService;
    private final CostCalculator costCalculator;
    private final LlmProperties llmProperties;
    private final Clock clock;
    private final TicketDraftReplyRepository ticketDraftReplyRepository;

    public TicketDraftReplyService(@Qualifier("draftReplyChatClient") ChatClient chatClient, OrderService orderService, CostCalculator costCalculator, LlmProperties llmProperties, Clock clock, TicketDraftReplyRepository ticketDraftReplyRepository) {
        this.chatClient = chatClient;
        this.orderService = orderService;
        this.costCalculator = costCalculator;
        this.llmProperties = llmProperties;
        this.clock = clock;
        this.ticketDraftReplyRepository = ticketDraftReplyRepository;
    }

    public TicketDraftReply draftReply(Ticket ticket, TicketTriage triage) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(triage, "triage is required for a draft reply");

        Optional<OrderSummary> order = orderService.findCustomerOrder(
                triage.getOrderNumber(),
                ticket.getCustomerEmail());
        String orderText = order.map(this::formatOrder).orElse("not found");

        long start = System.nanoTime();
        ChatResponse result = chatClient.prompt()
                .user(u -> u.text(
                                """
                                        <ticket>
                                        Subject: {subject}
                                        Body: {body}
                                        </ticket>
                                        <triage>
                                        Category: {category}
                                        Summary: {summary}
                                        Escalate: {escalate}
                                        Escalate reason: {escalateReason}
                                        </triage>
                                        <order>
                                        {order}
                                        </order>
                                """)
                        .param("subject", ticket.getSubject())
                        .param("body", ticket.getBody())
                        .param("category", triage.getCategory())
                        .param("summary", triage.getSummary())
                        .param("escalate", triage.isEscalate())
                        .param("escalateReason", triage.getEscalateReason() == null ? "none" : triage.getEscalateReason())
                        .param("order", orderText))
                .call()
                .chatResponse();

        int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);

        Objects.requireNonNull(result, "Chat response is null");
        Objects.requireNonNull(result.getResult(), "Chat response has no result");
        Usage usage = result.getMetadata().getUsage();
        BigDecimal cost = costCalculator.cost(usage.getPromptTokens(), usage.getCompletionTokens());
        LlmCallMetrics metrics = LlmCallMetrics.from(result, cost, latencyMs);

        DraftReply draftReply = new DraftReply(result.getResult().getOutput().getText(), orderText, metrics);
        TicketDraftReply ticketDraftReply = TicketDraftReply.of(
                ticket.getId(),
                draftReply,
                llmProperties.draft().replyVersion(),
                Instant.now(clock));
        return ticketDraftReplyRepository.save(ticketDraftReply);
    }

    private String formatOrder(OrderSummary o) {
        return """
            Number: %s
            Status: %s
            Eta: %s
            Created at: %s
            """.formatted(o.orderNumber(), o.status(), o.eta() == null ? "unknown" : o.eta(), LocalDate.ofInstant(o.createdAt(), ZoneOffset.UTC));
    }
}
