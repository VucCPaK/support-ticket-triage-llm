package dev.kyrylo.ticket_triage.triage;

import dev.kyrylo.ticket_triage.common.CostCalculator;
import dev.kyrylo.ticket_triage.config.LlmProperties;
import dev.kyrylo.ticket_triage.ticket.Ticket;
import lombok.AllArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
public class TicketTriageService {
    private final TicketTriageRepository ticketTriageRepository;
    private final ChatClient chatClient;
    private final CostCalculator costCalculator;
    private final LlmProperties llmProperties;
    private final Clock clock;

    public TicketTriageService(TicketTriageRepository ticketTriageRepository,
                               @Qualifier("triageChatClient") ChatClient chatClient,
                               CostCalculator costCalculator, LlmProperties llmProperties,
                               Clock clock) {
        this.ticketTriageRepository = ticketTriageRepository;
        this.chatClient = chatClient;
        this.costCalculator = costCalculator;
        this.llmProperties = llmProperties;
        this.clock = clock;
    }


    public TicketTriage triageTicket(String customerPlan, Ticket ticket) {
        long start = System.nanoTime();
        ResponseEntity<ChatResponse, TriageResult> result = chatClient.prompt()
                .user(u -> u.text(
                                """
                                Customer plan: {customerPlan}
                                <ticket>
                                Subject: {subject}
                                Body: {body}
                                </ticket>
                                """)
                        .param("customerPlan", customerPlan)
                        .param("subject", ticket.getSubject())
                        .param("body", ticket.getBody()))
                .call()
                .responseEntity(TriageResult.class);
        int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);

        Objects.requireNonNull(result.response(), "Usage response is null");
        Usage usage = result.response().getMetadata().getUsage();
        BigDecimal cost = costCalculator.cost(usage.getPromptTokens(), usage.getCompletionTokens());
        LlmCallMetrics metrics = LlmCallMetrics.from(result.response(), cost, latencyMs);

        TicketTriage ticketTriage = TicketTriage.of(
                ticket.getId(),
                result.entity(),
                metrics,
                llmProperties.triage().promptVersion(),
                Instant.now(clock));
        return ticketTriageRepository.save(ticketTriage);
    }
}
