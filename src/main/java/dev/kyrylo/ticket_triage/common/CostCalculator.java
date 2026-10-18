package dev.kyrylo.ticket_triage.common;

import dev.kyrylo.ticket_triage.config.LlmProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class CostCalculator {
    private final LlmProperties llmProperties;
    private final String model;

    public CostCalculator(@Value("${spring.ai.openai.chat.model}") String model,
                          LlmProperties llmProperties) {
        Objects.requireNonNull(llmProperties.pricing().get(model), "No price configured for model " + model);
        this.model = model;
        this.llmProperties = llmProperties;
    }

    public BigDecimal cost(int inputTokens, int outputTokens) {
        LlmProperties.ModelPrice price = llmProperties.pricing().get(model);

        BigDecimal priceInput = price.input();
        BigDecimal priceOutput = price.output();

        BigDecimal inTkns = BigDecimal.valueOf(inputTokens);
        BigDecimal outTkns = BigDecimal.valueOf(outputTokens);

        return inTkns.multiply(priceInput).add(outTkns.multiply(priceOutput)).movePointLeft(6);
    }
}
