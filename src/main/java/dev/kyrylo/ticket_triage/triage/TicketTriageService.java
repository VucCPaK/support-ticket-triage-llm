package dev.kyrylo.ticket_triage.triage;

import dev.kyrylo.ticket_triage.llm.CostCalculator;
import dev.kyrylo.ticket_triage.llm.FallbackCaller;
import dev.kyrylo.ticket_triage.llm.LlmCallMetrics;
import dev.kyrylo.ticket_triage.llm.LlmProperties;
import dev.kyrylo.ticket_triage.ticket.Ticket;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
public class TicketTriageService {
    private final TicketTriageRepository ticketTriageRepository;
    private final ChatClient chatClient;
    private final ChatClient fallbackChatClient;
    private final FallbackCaller fallbackCaller;
    private final CostCalculator costCalculator;
    private final LlmProperties llmProperties;
    private final Clock clock;

    public TicketTriageService(TicketTriageRepository ticketTriageRepository,
                               @Qualifier("triageChatClient") ChatClient chatClient,
                               @Qualifier("triageFallbackChatClient") ChatClient fallbackChatClient,
                               FallbackCaller fallbackCaller,
                               CostCalculator costCalculator, LlmProperties llmProperties,
                               Clock clock) {
        this.ticketTriageRepository = ticketTriageRepository;
        this.chatClient = chatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.fallbackCaller = fallbackCaller;
        this.costCalculator = costCalculator;
        this.llmProperties = llmProperties;
        this.clock = clock;
    }

    public TicketTriage triageTicket(String customerPlan, Ticket ticket) {
        int promptVersion = llmProperties.triage().promptVersion();
        // From prompt v2 the ENTERPRISE modifier is applied here, not by the model: the eval showed that with
        // less reasoning the model forgets it. Code never forgets, and the ticket text cannot influence it.
        // v1 still applies it in the prompt, so v1 keeps the plan in the message and skips the code modifier.
        boolean planModifierInCode = promptVersion >= 2;

        TriageCall call = fallbackCaller.call("triage",
                () -> callModel(chatClient, customerPlan, ticket, planModifierInCode),
                () -> callModel(fallbackChatClient, customerPlan, ticket, planModifierInCode));

        TriageResult triage = call.result();
        if (planModifierInCode && "ENTERPRISE".equals(customerPlan) && triage.priority() != null) {
            triage = triage.withPriority(triage.priority().raise());
        }

        return ticketTriageRepository.save(TicketTriage.of(
                ticket.getId(),
                triage,
                call.metrics(),
                promptVersion,
                Instant.now(clock)));
    }

    private TriageCall callModel(ChatClient client, String customerPlan, Ticket ticket, boolean planModifierInCode) {
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
        int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);

        ChatResponse response = Objects.requireNonNull(result.response(), "Chat response is null");
        TriageResult triage = Objects.requireNonNull(result.entity(), "Triage result is null");
        return new TriageCall(triage, LlmCallMetrics.from(response, costCalculator, latencyMs));
    }
}
