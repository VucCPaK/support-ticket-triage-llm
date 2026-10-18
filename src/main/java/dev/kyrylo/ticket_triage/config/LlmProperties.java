package dev.kyrylo.ticket_triage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

@ConfigurationProperties("llm")
public record LlmProperties(Triage triage, Map<String, ModelPrice> pricing) {
    public record Triage(int promptVersion) {}
    public record ModelPrice(BigDecimal input, BigDecimal output) {}
}
