package dev.kyrylo.ticket_triage.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

@ConfigurationProperties("llm")
public record LlmProperties(Triage triage, Draft draft, Fallback fallback, Budget budget,
                            Map<String, ModelPrice> pricing) {
    public record Triage(int promptVersion) {}
    public record Draft(int replyVersion) {}
    /** Model used when the main one is unavailable (rate limit, server error, timeout). */
    public record Fallback(String model) {}
    /** Hard daily cap on LLM spend across triage and drafts, in USD. */
    public record Budget(BigDecimal dailyUsd) {}
    public record ModelPrice(BigDecimal input, BigDecimal output) {}
}
