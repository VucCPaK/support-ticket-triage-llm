package dev.kyrylo.ticket_triage.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param interval     pause between two runs of the job
 * @param lease        how long a claimed ticket is reserved for the worker processing it
 * @param staleAfter   a ticket still NEW after this long was abandoned mid-call (the app stopped)
 * @param maxAttempts  after this many retries a ticket is left for a human
 * @param batchSize    tickets claimed per run, so one run cannot burst the provider's rate limit
 */
@ConfigurationProperties("tickets.recovery")
public record RecoveryProperties(boolean enabled, Duration interval, Duration lease, Duration staleAfter,
                                 int maxAttempts, int batchSize) {
}
