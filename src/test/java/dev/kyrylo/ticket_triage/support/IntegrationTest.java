package dev.kyrylo.ticket_triage.support;

import dev.kyrylo.ticket_triage.TestcontainersConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real PostgreSQL in Docker (Testcontainers) with all Flyway migrations, and the fake model.
 * One Spring context, so one container, shared by every test class that extends this.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",    // never the real key from .env; the fake answers anyway
        "tickets.recovery.enabled=true",
        "tickets.recovery.interval=PT1H"        // the job never fires on its own: tests call recover()
})
@Import({TestcontainersConfiguration.class, FakeChatModelConfiguration.class})
public abstract class IntegrationTest {

    @Autowired
    protected FakeChatModel fakeModel;

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected MeterRegistry meterRegistry;

    // Our code uses its own short transactions, so a rolled-back test transaction would not isolate tests
    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE ticket_draft_reply, ticket_triage, tickets").update();
        fakeModel.reset();
    }

    /** Counters live as long as the shared context, so tests compare before and after, never absolute values. */
    protected double counter(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}
