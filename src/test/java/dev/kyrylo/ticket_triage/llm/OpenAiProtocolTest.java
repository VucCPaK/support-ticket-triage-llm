package dev.kyrylo.ticket_triage.llm;

import dev.kyrylo.ticket_triage.TestcontainersConfiguration;
import dev.kyrylo.ticket_triage.support.FakeOpenAiServer;
import dev.kyrylo.ticket_triage.support.FakeOpenAiServer.Reply;
import dev.kyrylo.ticket_triage.ticket.CreateTicketRequest;
import dev.kyrylo.ticket_triage.ticket.TicketDetails;
import dev.kyrylo.ticket_triage.ticket.TicketService;
import dev.kyrylo.ticket_triage.ticket.TicketStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static dev.kyrylo.ticket_triage.support.FakeChatModel.triageJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 2: no fake ChatModel. The real Spring AI client and OpenAI SDK call a fake OpenAI server, so these tests
 * check what level 1 cannot: which exception the SDK really throws for a 429, a 400 or a timeout, and whether
 * ModelErrors classifies it the way the fallback expects.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.openai.max-retries=0",     // fail fast: the retries themselves are the SDK's, not ours
        "spring.ai.openai.timeout=2s",
        "tickets.recovery.enabled=false"
})
@Import(TestcontainersConfiguration.class)
class OpenAiProtocolTest {

    private static final String MAIN = "gpt-6-luna";
    private static final FakeOpenAiServer SERVER = new FakeOpenAiServer();

    @DynamicPropertySource
    static void openAiBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", SERVER::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    TicketService ticketService;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void rateLimitOnTheMainModelSwitchesToTheFallback() {
        double before = fallbacks("RateLimitException");
        SERVER.respond((model, body) -> MAIN.equals(model)
                ? Reply.error(429, "rate_limit_exceeded")
                : answer(model, body));

        TicketDetails details = create();

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(details.triage().getModel()).isEqualTo("gpt-5-nano");
        assertThat(SERVER.requestedModels()).startsWith(MAIN, "gpt-5-nano");
        assertThat(fallbacks("RateLimitException")).isEqualTo(before + 1);
    }

    @Test
    void serverErrorOnTheMainModelSwitchesToTheFallback() {
        SERVER.respond((model, body) -> MAIN.equals(model)
                ? Reply.error(503, "service_unavailable")
                : answer(model, body));

        assertThat(create().ticket().getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(SERVER.requestedModels()).startsWith(MAIN, "gpt-5-nano");
    }

    @Test
    void timeoutOnTheMainModelSwitchesToTheFallback() {
        SERVER.respond((model, body) -> MAIN.equals(model) && FakeOpenAiServer.isTriage(body)
                ? answer(model, body).after(Duration.ofSeconds(4))   // longer than the 2s timeout
                : answer(model, body));

        TicketDetails details = create();

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(details.triage().getModel()).isEqualTo("gpt-5-nano");
    }

    @Test
    void badRequestIsOurErrorAndDoesNotSwitch() {
        SERVER.respond((model, body) -> MAIN.equals(model)
                ? Reply.error(400, "invalid_request_error")
                : answer(model, body));

        TicketDetails details = create();

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGE_FAILED);
        assertThat(details.ticket().getLastError()).contains("BadRequestException");
        assertThat(SERVER.requestedModels()).containsOnly(MAIN);
    }

    @Test
    void aNormalAnswerIsParsedAndPricedThroughTheRealClient() {
        SERVER.respond(OpenAiProtocolTest::answer);

        TicketDetails details = create();

        assertThat(details.triage().getModel()).isEqualTo(MAIN);
        assertThat(details.triage().getInputTokens()).isEqualTo(100);
        assertThat(details.triage().getUsdCost()).isPositive();
        assertThat(details.draftReply()).isNotNull();
    }

    private static Reply answer(String model, String body) {
        return Reply.ok(model, FakeOpenAiServer.isTriage(body)
                ? triageJson("ORDER", "MEDIUM", "B456")
                : "Hello,\n\nWe are looking into it.\n\nBest regards,\n[Agent name]");
    }

    private TicketDetails create() {
        return ticketService.create(new CreateTicketRequest("liam.byrne@example.com", "Subject", "Where is B456?"));
    }

    private double fallbacks(String reason) {
        Counter counter = meterRegistry.find("llm.fallback").tags("step", "triage", "reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }
}
