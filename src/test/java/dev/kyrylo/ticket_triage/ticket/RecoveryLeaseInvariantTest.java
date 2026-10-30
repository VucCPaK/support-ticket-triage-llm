package dev.kyrylo.ticket_triage.ticket;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit test, no Spring: the startup check that keeps the lease longer than one ticket can take. */
class RecoveryLeaseInvariantTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final int RETRIES = 1;  // worst case 12s x 2 x 4 requests = 96s, required with margin 192s

    @Test
    void currentSettingsPass() {
        assertThatCode(() -> TicketRecoveryJob.requireLongerThanWorstCase(
                properties(Duration.ofMinutes(4), Duration.ofMinutes(5)), TIMEOUT, RETRIES))
                .doesNotThrowAnyException();
    }

    @Test
    void aShortLeaseStopsTheStartup() {
        assertThatThrownBy(() -> TicketRecoveryJob.requireLongerThanWorstCase(
                properties(Duration.ofMinutes(1), Duration.ofMinutes(5)), TIMEOUT, RETRIES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tickets.recovery.lease");
    }

    @Test
    void raisingTheTimeoutLaterWithoutALongerLeaseStopsTheStartup() {
        // The scenario the check exists for: someone raises the model timeout and forgets the lease
        assertThatThrownBy(() -> TicketRecoveryJob.requireLongerThanWorstCase(
                properties(Duration.ofMinutes(4), Duration.ofMinutes(5)), Duration.ofSeconds(30), RETRIES))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aShortStaleWindowStopsTheStartupToo() {
        assertThatThrownBy(() -> TicketRecoveryJob.requireLongerThanWorstCase(
                properties(Duration.ofMinutes(4), Duration.ofMinutes(2)), TIMEOUT, RETRIES))
                .hasMessageContaining("tickets.recovery.stale-after");
    }

    private static RecoveryProperties properties(Duration lease, Duration staleAfter) {
        return new RecoveryProperties(true, Duration.ofMinutes(1), lease, staleAfter, 5, 10);
    }
}
