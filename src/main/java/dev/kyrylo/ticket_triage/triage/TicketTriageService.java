package dev.kyrylo.ticket_triage.triage;

import dev.kyrylo.ticket_triage.common.CostCalculator;
import dev.kyrylo.ticket_triage.common.LlmMetrics;
import dev.kyrylo.ticket_triage.common.ModelErrors;
import dev.kyrylo.ticket_triage.config.LlmProperties;
import dev.kyrylo.ticket_triage.ticket.Ticket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Slf4j
@Service
public class TicketTriageService {
    private final TicketTriageRepository ticketTriageRepository;
    private final ChatClient chatClient;
    private final ChatClient fallbackChatClient;
    private final CostCalculator costCalculator;
    private final LlmProperties llmProperties;
    private final Clock clock;
    private final LlmMetrics llmMetrics;

    public TicketTriageService(TicketTriageRepository ticketTriageRepository,
                               @Qualifier("triageChatClient") ChatClient chatClient,
                               @Qualifier("triageFallbackChatClient") ChatClient fallbackChatClient,
                               CostCalculator costCalculator, LlmProperties llmProperties,
                               Clock clock, LlmMetrics llmMetrics) {
        this.ticketTriageRepository = ticketTriageRepository;
        this.chatClient = chatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.costCalculator = costCalculator;
        this.llmProperties = llmProperties;
        this.clock = clock;
        this.llmMetrics = llmMetrics;
    }


    public TicketTriage triageTicket(String customerPlan, Ticket ticket) {
        int promptVersion = llmProperties.triage().promptVersion();
        // From prompt v2 the ENTERPRISE modifier is applied here, not by the model: the eval showed that with
        // less reasoning the model forgets it. Code never forgets, and the ticket text cannot influence it.
        // v1 still applies it in the prompt, so v1 keeps the plan in the message and skips the code modifier.
        boolean planModifierInCode = promptVersion >= 2;

        ModelAnswer answer;
        try {
            answer = callModel(chatClient, customerPlan, ticket, planModifierInCode);
        } catch (RuntimeException e) {
            if (!ModelErrors.isProviderUnavailable(e)) {
                throw e;  // our request is wrong: the fallback model would fail the same way
            }
            log.warn("Main triage model failed for ticket {}, trying the fallback model", ticket.getId(), e);
            llmMetrics.recordFallback("triage", e);
            answer = callModel(fallbackChatClient, customerPlan, ticket, planModifierInCode);
        }

        Objects.requireNonNull(answer.result().response(), "Chat response is null");
        LlmCallMetrics metrics = LlmCallMetrics.from(answer.result().response(), costCalculator, answer.latencyMs());

        TriageResult triage = Objects.requireNonNull(answer.result().entity(), "Triage result is null");
        if (planModifierInCode && "ENTERPRISE".equals(customerPlan) && triage.priority() != null) {
            triage = triage.withPriority(triage.priority().raise());
        }

        TicketTriage ticketTriage = TicketTriage.of(
                ticket.getId(),
                triage,
                metrics,
                promptVersion,
                Instant.now(clock));
        return ticketTriageRepository.save(ticketTriage);
    }

    private record ModelAnswer(ResponseEntity<ChatResponse, TriageResult> result, int latencyMs) {}

    private ModelAnswer callModel(ChatClient client, String customerPlan, Ticket ticket, boolean planModifierInCode) {
        long start = System.nanoTime();
        ResponseEntity<ChatResponse, TriageResult> result = client.prompt()
                .user(u -> {
                    if (planModifierInCode) {
                        u.text("""
                                <ticket>
                                Subject: {subject}
                                Body: {body}
                                </ticket>
                                """);
                    } else {
                        u.text("""
                                Customer plan: {customerPlan}
                                <ticket>
                                Subject: {subject}
                                Body: {body}
                                </ticket>
                                """).param("customerPlan", customerPlan);
                    }
                    u.param("subject", ticket.getSubject())
                            .param("body", ticket.getBody());
                })
                .call()
                .responseEntity(TriageResult.class);
        return new ModelAnswer(result, (int) ((System.nanoTime() - start) / 1_000_000));
    }
}
