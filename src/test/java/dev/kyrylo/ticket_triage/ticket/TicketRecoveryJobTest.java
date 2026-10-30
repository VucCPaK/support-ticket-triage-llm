package dev.kyrylo.ticket_triage.ticket;

import com.openai.errors.OpenAIIoException;
import dev.kyrylo.ticket_triage.support.IntegrationTest;
import dev.kyrylo.ticket_triage.triage.TicketTriageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Level 1: the recovery job against real PostgreSQL (FOR UPDATE SKIP LOCKED, leases, backoff). */
class TicketRecoveryJobTest extends IntegrationTest {

    @Autowired
    TicketRecoveryJob recoveryJob;

    @Autowired
    TicketRepository ticketRepository;

    @Autowired
    TicketTriageRepository ticketTriageRepository;

    @Autowired
    TicketService ticketService;

    @Test
    void failedTicketIsTriagedOnTheNextRun() {
        UUID id = failedTicket();

        recoveryJob.recover();

        Ticket ticket = reload(id);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(ticket.getAttempts()).isEqualTo(1);
        assertThat(ticket.getLastError()).isNull();
        assertThat(ticketTriageRepository.findById(id)).isPresent();
    }

    @Test
    void aClaimedTicketIsNotTakenAgainWhileLeasedOrBackingOff() {
        UUID id = failedTicket();
        fakeModel.onTriage(prompt -> {
            throw new OpenAIIoException("Provider is down");  // main and fallback both fail
        });

        recoveryJob.recover();
        int callsAfterFirstRun = fakeModel.triageCalls().size();
        recoveryJob.recover();  // right away: the ticket is still leased

        Ticket ticket = reload(id);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.TRIAGE_FAILED);
        assertThat(ticket.getAttempts()).isEqualTo(1);
        assertThat(ticket.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofMinutes(3)));  // 4m lease
        assertThat(fakeModel.triageCalls()).hasSize(callsAfterFirstRun);
    }

    @Test
    void aTicketOutOfAttemptsIsLeftForAHuman() {
        UUID id = failedTicket();
        jdbcClient.sql("UPDATE tickets SET attempts = 5 WHERE id = :id").param("id", id).update();

        recoveryJob.recover();

        assertThat(reload(id).getStatus()).isEqualTo(TicketStatus.TRIAGE_FAILED);
        assertThat(fakeModel.triageCalls()).isEmpty();
    }

    @Test
    void onlyNewTicketsOlderThanStaleAfterAreTakenOver() {
        UUID abandoned = newTicketCreatedAt(Instant.now().minus(Duration.ofMinutes(10)));  // the app stopped mid-call
        UUID inProgress = newTicketCreatedAt(Instant.now());                              // still being processed

        recoveryJob.recover();

        assertThat(reload(abandoned).getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(reload(inProgress).getStatus()).isEqualTo(TicketStatus.NEW);
    }

    @Test
    void aSavedTriageIsReusedNotPaidForAgain() {
        // A crash after the triage was saved but before the ticket status was: triage exists, ticket is NEW
        TicketDetails done = ticketService.create(new CreateTicketRequest("liam.byrne@example.com", "S", "Where is B456?"));
        UUID id = done.ticket().getId();
        jdbcClient.sql("DELETE FROM ticket_draft_reply WHERE ticket_id = :id").param("id", id).update();
        jdbcClient.sql("UPDATE tickets SET status = 'NEW', created_at = now() - interval '10 minutes' WHERE id = :id")
                .param("id", id).update();
        fakeModel.reset();

        recoveryJob.recover();

        assertThat(reload(id).getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(fakeModel.triageCalls()).isEmpty();   // reused
        assertThat(fakeModel.draftCalls()).hasSize(1);   // only the missing draft was made
    }

    @Test
    void skippedTicketsWaitWhileTheBudgetIsUsedUp() {
        UUID id = failedTicket();
        TicketDetails expensive = ticketService.create(new CreateTicketRequest("liam.byrne@example.com", "S", "B456"));
        jdbcClient.sql("UPDATE ticket_triage SET usd_cost = 5 WHERE ticket_id = :id")
                .param("id", expensive.ticket().getId()).update();
        fakeModel.reset();

        recoveryJob.recover();

        assertThat(reload(id).getAttempts()).isZero();  // not claimed: an attempt would be wasted
        assertThat(fakeModel.triageCalls()).isEmpty();
    }

    private UUID failedTicket() {
        Ticket ticket = Ticket.open("liam.byrne@example.com", "Subject", "Where is my order B456?", Instant.now());
        ticket.markTriageFailed("OpenAIIoException: Provider is down");
        return ticketRepository.save(ticket).getId();
    }

    private UUID newTicketCreatedAt(Instant createdAt) {
        return ticketRepository.save(Ticket.open("liam.byrne@example.com", "Subject", "Where is B456?", createdAt)).getId();
    }

    private Ticket reload(UUID id) {
        return ticketRepository.findById(id).orElseThrow();
    }
}
