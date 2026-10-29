package dev.kyrylo.ticket_triage.common;

import dev.kyrylo.ticket_triage.config.LlmProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Daily cap on LLM spend. The cost of a call is known only after it, so the check runs before:
 * once today's spend reaches the cap, new calls are not made. The call that crosses the cap still
 * completes and is used (it is already paid for), so the overshoot is at most one ticket's cost.
 */
@Component
public class BudgetGuard {
    private final JdbcClient jdbcClient;
    private final LlmProperties llmProperties;
    private final Clock clock;

    public BudgetGuard(JdbcClient jdbcClient, LlmProperties llmProperties, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.llmProperties = llmProperties;
        this.clock = clock;
    }

    public boolean isExhausted() {
        return spentToday().compareTo(llmProperties.budget().dailyUsd()) >= 0;
    }

    /** Spend since midnight UTC across both LLM steps; the database is shared by all app instances. */
    public BigDecimal spentToday() {
        Instant startOfDay = LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(usd_cost), 0) FROM (
                            SELECT usd_cost FROM ticket_triage WHERE created_at >= :since
                            UNION ALL
                            SELECT usd_cost FROM ticket_draft_reply WHERE created_at >= :since
                        ) spend
                        """)
                .param("since", startOfDay)
                .query(BigDecimal.class)
                .single();
    }
}
