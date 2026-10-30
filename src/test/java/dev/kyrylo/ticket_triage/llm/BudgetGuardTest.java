package dev.kyrylo.ticket_triage.llm;

import dev.kyrylo.ticket_triage.support.IntegrationTest;
import dev.kyrylo.ticket_triage.ticket.CreateTicketRequest;
import dev.kyrylo.ticket_triage.ticket.TicketDetails;
import dev.kyrylo.ticket_triage.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The budget query runs on real PostgreSQL: binding an Instant failed there and nowhere else. */
class BudgetGuardTest extends IntegrationTest {

    @Autowired
    BudgetGuard budgetGuard;

    @Autowired
    TicketService ticketService;

    @Test
    void spentTodaySumsTriageAndDraftOfToday() {
        createTicket();

        // The fake reports 100 input and 20 output tokens of gpt-6-luna per call:
        // 100 * 0.10 + 20 * 0.50 = 20 USD per 1M tokens = 0.00002 USD, for the triage and the draft
        assertThat(budgetGuard.spentToday()).isEqualByComparingTo(new BigDecimal("0.00004"));
    }

    @Test
    void spendBeforeMidnightUtcDoesNotCount() {
        TicketDetails details = createTicket();
        jdbcClient.sql("UPDATE ticket_triage SET usd_cost = 5, created_at = :yesterday WHERE ticket_id = :id")
                .param("yesterday", LocalDate.now(ZoneOffset.UTC).atStartOfDay().atOffset(ZoneOffset.UTC).minusMinutes(1))
                .param("id", details.ticket().getId())
                .update();

        assertThat(budgetGuard.spentToday()).isEqualByComparingTo(new BigDecimal("0.00002"));  // only the draft
        assertThat(budgetGuard.isExhausted()).isFalse();
    }

    private TicketDetails createTicket() {
        return ticketService.create(new CreateTicketRequest("liam.byrne@example.com", "Subject", "Where is B456?"));
    }
}
