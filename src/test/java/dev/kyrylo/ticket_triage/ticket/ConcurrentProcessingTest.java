package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.llm.LlmCallMetrics;
import dev.kyrylo.ticket_triage.support.IntegrationTest;
import dev.kyrylo.ticket_triage.triage.TicketCategory;
import dev.kyrylo.ticket_triage.triage.TicketPriority;
import dev.kyrylo.ticket_triage.triage.TicketTriage;
import dev.kyrylo.ticket_triage.triage.TicketTriageRepository;
import dev.kyrylo.ticket_triage.triage.TriageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens if a lease expires anyway and two workers process one ticket: nothing is corrupted.
 * These are the guarantees behind the recovery job, tested without timing tricks.
 */
class ConcurrentProcessingTest extends IntegrationTest {

    @Autowired
    TicketRepository ticketRepository;

    @Autowired
    TicketTriageRepository ticketTriageRepository;

    @Test
    void aStaleWorkerCannotOverwriteATicketClaimedAgain() {
        UUID id = ticketRepository.save(Ticket.open("liam.byrne@example.com", "S", "B", Instant.now())).getId();
        Ticket staleCopy = ticketRepository.findById(id).orElseThrow();  // worker A loaded it

        // Meanwhile the claim query takes it again; it bumps the version by hand, as in TicketRecoveryJob
        jdbcClient.sql("UPDATE tickets SET attempts = attempts + 1, version = version + 1 WHERE id = :id")
                .param("id", id).update();

        staleCopy.markTriageFailed("late result of worker A");
        assertThatThrownBy(() -> ticketRepository.save(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(ticketRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TicketStatus.NEW);
    }

    @Test
    void aSecondTriageResultFailsInsteadOfOverwritingTheFirst() {
        UUID id = ticketRepository.save(Ticket.open("liam.byrne@example.com", "S", "B", Instant.now())).getId();
        ticketTriageRepository.save(triage(id, TicketCategory.ORDER));

        assertThatThrownBy(() -> ticketTriageRepository.save(triage(id, TicketCategory.BILLING)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(ticketTriageRepository.findById(id).orElseThrow().getCategory()).isEqualTo(TicketCategory.ORDER);
    }

    private static TicketTriage triage(UUID ticketId, TicketCategory category) {
        TriageResult result = new TriageResult(category, TicketPriority.MEDIUM, null, false, null, "summary");
        LlmCallMetrics metrics = new LlmCallMetrics("gpt-6-luna", 100, 20, null, new BigDecimal("0.00002"), 1000);
        return TicketTriage.of(ticketId, result, metrics, 3, Instant.now());
    }
}
