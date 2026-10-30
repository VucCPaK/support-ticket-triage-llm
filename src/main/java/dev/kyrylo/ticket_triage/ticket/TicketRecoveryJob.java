package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.llm.BudgetGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Triages again the tickets that were left without a result: NEW for too long (the app stopped
 * mid-call), TRIAGE_FAILED (the model was down) and TRIAGE_SKIPPED (the budget was used up).
 *
 * Safe with several app instances: the claim query locks rows with FOR UPDATE SKIP LOCKED, so two
 * instances never take the same ticket, and sets next_attempt_at as a lease, so a claimed ticket is
 * not taken again while it is processed. The model is called after the claim, outside any transaction.
 *
 * If a lease still expires mid-processing, nothing is corrupted: the ticket's @Version rejects the stale
 * worker's save, and triage and draft rows are insert-only, so the first result is never overwritten.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tickets.recovery.enabled", havingValue = "true")
public class TicketRecoveryJob {
    // next_attempt_at = now + max(lease, backoff): the lease reserves the ticket while it is processed,
    // the backoff (1, 4, 16, 64, 256 minutes) spaces out retries against a broken provider.
    // version is incremented by hand: this native update bypasses JPA, and without it a stale worker's
    // save would still match the old version. SET expressions read the old values of the row.
    private static final String CLAIM_SQL = """
            UPDATE tickets
            SET status = 'NEW',
                attempts = attempts + 1,
                version = version + 1,
                next_attempt_at = :now + GREATEST(make_interval(secs => :leaseSeconds),
                                                  interval '1 minute' * power(4, attempts))
            WHERE id IN (
                SELECT id FROM tickets
                WHERE attempts < :maxAttempts
                  AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                  AND (status IN ('TRIAGE_FAILED', 'TRIAGE_SKIPPED')
                       OR (status = 'NEW' AND created_at < :staleBefore))
                ORDER BY created_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcClient jdbcClient;
    private final TicketRepository ticketRepository;
    private final TicketProcessor ticketProcessor;
    private final BudgetGuard budgetGuard;
    private final RecoveryProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // Model calls one ticket can make in the worst case: triage on the main model, triage on the fallback,
    // and a draft whose tool call takes two requests. Update it when the pipeline gets another model call.
    static final int MAX_MODEL_REQUESTS_PER_TICKET = 4;

    public TicketRecoveryJob(JdbcClient jdbcClient, TicketRepository ticketRepository, TicketProcessor ticketProcessor,
                             BudgetGuard budgetGuard, RecoveryProperties properties, MeterRegistry meterRegistry,
                             Clock clock,
                             @Value("${spring.ai.openai.timeout}") Duration requestTimeout,
                             @Value("${spring.ai.openai.max-retries}") int maxRetries) {
        requireLongerThanWorstCase(properties, requestTimeout, maxRetries);
        this.jdbcClient = jdbcClient;
        this.ticketRepository = ticketRepository;
        this.ticketProcessor = ticketProcessor;
        this.budgetGuard = budgetGuard;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tickets.recovery.interval}", initialDelayString = "${tickets.recovery.interval}")
    public void recover() {
        // Nothing can succeed until the budget resets: do not spend the tickets' attempts on it
        if (budgetGuard.isExhausted()) {
            return;
        }
        List<UUID> claimed = claim();
        if (claimed.isEmpty()) {
            return;
        }
        log.info("Recovering {} tickets", claimed.size());
        for (Ticket ticket : ticketRepository.findAllById(claimed)) {
            try {
                TicketStatus status = ticketProcessor.process(ticket).ticket().getStatus();
                record(status.name());
                if (status != TicketStatus.TRIAGED && ticket.getAttempts() >= properties.maxAttempts()) {
                    log.warn("Ticket {} is still {} after {} attempts, left for a human",
                            ticket.getId(), status, ticket.getAttempts());
                    record("gave_up");
                }
            } catch (RuntimeException e) {
                // One broken ticket must not stop the rest; its lease expires and it is retried later
                log.error("Recovery of ticket {} failed", ticket.getId(), e);
                record("error");
            }
        }
    }

    /**
     * Fails the startup when a claimed ticket could still be processing after its lease or stale window ends:
     * another worker would take it too. The worst case comes from the same settings the model calls use,
     * so raising a timeout later without a longer lease stops the app instead of silently double-processing.
     */
    static void requireLongerThanWorstCase(RecoveryProperties properties, Duration requestTimeout, int maxRetries) {
        Duration worstCase = requestTimeout.multipliedBy((maxRetries + 1L) * MAX_MODEL_REQUESTS_PER_TICKET);
        // x2 covers what the formula does not count: the SDK's pauses between retries and the database writes
        Duration required = worstCase.multipliedBy(2);
        for (var window : List.of(Map.entry("lease", properties.lease()),
                                  Map.entry("stale-after", properties.staleAfter()))) {
            if (window.getValue().compareTo(required) < 0) {
                throw new IllegalStateException(("tickets.recovery.%s is %s, but one ticket can take up to %s "
                        + "(timeout %s, %d retries, %d model requests), so it must be at least %s. "
                        + "Increase it or lower the timeouts.").formatted(window.getKey(), window.getValue(),
                        worstCase, requestTimeout, maxRetries, MAX_MODEL_REQUESTS_PER_TICKET, required));
            }
        }
    }

    /** One statement: lock free rows, move them back to NEW with a lease, and return their ids. */
    private List<UUID> claim() {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        return jdbcClient.sql(CLAIM_SQL)
                .param("now", now)
                .param("staleBefore", now.minus(properties.staleAfter()))
                .param("leaseSeconds", properties.lease().toSeconds())
                .param("maxAttempts", properties.maxAttempts())
                .param("batchSize", properties.batchSize())
                .query(UUID.class)
                .list();
    }

    private void record(String outcome) {
        Counter.builder("ticket.recovery")
                .description("Tickets the recovery job processed, by the status they ended in")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
