package dev.kyrylo.ticket_triage.common;

import dev.kyrylo.ticket_triage.config.LlmProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

@Component
public class CostCalculator {
    private final Map<String, LlmProperties.ModelPrice> pricing;

    public CostCalculator(@Value("${spring.ai.openai.chat.model}") String model,
                          LlmProperties llmProperties) {
        this.pricing = llmProperties.pricing();
        // Fail at startup, not on the first paid call: every model we may call must have a price
        Objects.requireNonNull(pricing.get(model), "No price configured for model " + model);
        String fallbackModel = llmProperties.fallback().model();
        Objects.requireNonNull(pricing.get(fallbackModel), "No price configured for fallback model " + fallbackModel);
    }

    /**
     * Cost of one call by the model that actually answered. The provider may add a date suffix
     * (gpt-5-nano-2025-08-07), so the longest configured name the answer starts with wins.
     */
    public BigDecimal cost(String model, int inputTokens, int outputTokens) {
        LlmProperties.ModelPrice price = priceFor(model);
        return BigDecimal.valueOf(inputTokens).multiply(price.input())
                .add(BigDecimal.valueOf(outputTokens).multiply(price.output()))
                .movePointLeft(6);
    }

    private LlmProperties.ModelPrice priceFor(String model) {
        LlmProperties.ModelPrice exact = pricing.get(model);
        if (exact != null) {
            return exact;
        }
        return pricing.keySet().stream()
                .filter(name -> model.startsWith(name + "-"))
                .max(Comparator.comparingInt(String::length))
                .map(pricing::get)
                .orElseThrow(() -> new IllegalStateException("No price configured for model " + model));
    }
}
