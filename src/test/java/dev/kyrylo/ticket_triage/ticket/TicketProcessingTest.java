package dev.kyrylo.ticket_triage.ticket;

import com.openai.errors.OpenAIIoException;
import dev.kyrylo.ticket_triage.support.FakeChatModel;
import dev.kyrylo.ticket_triage.support.IntegrationTest;
import dev.kyrylo.ticket_triage.triage.TicketPriority;
import dev.kyrylo.ticket_triage.triage.TicketTriageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static dev.kyrylo.ticket_triage.support.FakeChatModel.triageJson;
import static org.assertj.core.api.Assertions.assertThat;

/** Level 1: the pipeline of a new ticket with a fake model and a real database. */
class TicketProcessingTest extends IntegrationTest {

    private static final String ENTERPRISE_EMAIL = "anna.murphy@example.com";  // seed data: ENTERPRISE plan
    private static final String PRO_EMAIL = "liam.byrne@example.com";          // seed data: PRO plan

    @Autowired
    TicketService ticketService;

    @Autowired
    TicketTriageRepository ticketTriageRepository;

    @Autowired
    TicketRepository ticketRepository;

    @Test
    void enterpriseCustomerGetsThePriorityRaisedInCode() {
        fakeModel.onTriage(prompt -> triageJson("ORDER", "HIGH", "A123"));

        TicketDetails details = create(ENTERPRISE_EMAIL, "Where is my order A123?");

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(details.triage().getPriority()).isEqualTo(TicketPriority.URGENT);
    }

    @Test
    void otherPlansKeepThePriorityTheModelGave() {
        fakeModel.onTriage(prompt -> triageJson("ORDER", "HIGH", "B456"));

        TicketDetails details = create(PRO_EMAIL, "Where is my order B456?");

        assertThat(details.triage().getPriority()).isEqualTo(TicketPriority.HIGH);
    }

    @Test
    void thePlanIsNotSentToTheModel() {
        // Prompt v2+: the modifier lives in code, so the ticket text cannot game it and it is never applied twice
        create(ENTERPRISE_EMAIL, "Where is my order A123?");

        String userMessage = fakeModel.triageCalls().getFirst().getUserMessage().getText();
        assertThat(userMessage).doesNotContain("ENTERPRISE").doesNotContain("Customer plan");
    }

    @Test
    void providerFailureSwitchesToTheFallbackModel() {
        double fallbacksBefore = counter("llm.fallback", "step", "triage", "reason", "OpenAIIoException");
        fakeModel.onTriage(prompt -> {
            if (FakeChatModel.MAIN_MODEL.equals(FakeChatModel.modelOf(prompt))) {
                throw new OpenAIIoException("Request timed out");
            }
            return triageJson("BILLING", "URGENT", "B456");
        });

        TicketDetails details = create(PRO_EMAIL, "I was charged twice for B456");

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(details.triage().getModel()).isEqualTo("gpt-5-nano");
        assertThat(fakeModel.triageCalls()).extracting(FakeChatModel::modelOf)
                .containsExactly("gpt-6-luna", "gpt-5-nano");
        assertThat(counter("llm.fallback", "step", "triage", "reason", "OpenAIIoException"))
                .isEqualTo(fallbacksBefore + 1);
    }

    @Test
    void anErrorInOurRequestDoesNotSwitchModels() {
        fakeModel.onTriage(prompt -> {
            throw new IllegalArgumentException("400 Bad Request: invalid schema");
        });

        TicketDetails details = create(PRO_EMAIL, "Where is my order B456?");

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGE_FAILED);
        assertThat(details.ticket().getLastError()).contains("invalid schema");
        assertThat(fakeModel.triageCalls()).hasSize(1);  // the fallback would have failed the same way
        assertThat(fakeModel.draftCalls()).isEmpty();    // no triage, no draft
    }

    @Test
    void anIncompleteAnswerFailsTheTriageButKeepsTheTicket() {
        fakeModel.onTriage(prompt -> "{\"category\": \"ORDER\"}");  // no priority, no summary

        TicketDetails details = create(PRO_EMAIL, "Where is my order B456?");

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGE_FAILED);
        assertThat(ticketRepository.findById(details.ticket().getId())).isPresent();
        assertThat(ticketTriageRepository.findById(details.ticket().getId())).isEmpty();
    }

    @Test
    void usedUpBudgetSkipsTheModelEntirely() {
        TicketDetails earlier = create(PRO_EMAIL, "Earlier ticket");
        jdbcClient.sql("UPDATE ticket_triage SET usd_cost = 5 WHERE ticket_id = :id")  // over the 1.00 daily cap
                .param("id", earlier.ticket().getId())
                .update();
        fakeModel.reset();

        TicketDetails details = create(PRO_EMAIL, "Where is my order B456?");

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGE_SKIPPED);
        assertThat(fakeModel.triageCalls()).isEmpty();
    }

    @Test
    void aSuccessfulTicketGetsTriageAndDraft() {
        TicketDetails details = create(PRO_EMAIL, "Where is my order B456?");

        assertThat(details.triage()).isNotNull();
        assertThat(details.draftReply()).isNotNull();
        assertThat(details.draftReply().getReplyText()).startsWith("Hello");
        assertThat(details.triage().getUsdCost()).isPositive();
    }

    private TicketDetails create(String email, String body) {
        return ticketService.create(new CreateTicketRequest(email, "Subject", body));
    }
}
