package dev.kyrylo.ticket_triage.triage;

import com.openai.models.completions.CompletionUsage;
import dev.kyrylo.ticket_triage.common.CostCalculator;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.math.BigDecimal;

/**
 * What one model call cost: which model actually answered, tokens, price and latency.
 */
public record LlmCallMetrics(
        String model,
        int inputTokens,
        int outputTokens,
        Integer reasoningTokens,  // null when the provider does not report it
        BigDecimal costUsd,
        int latencyMs
) {

    /**
     * The cost is derived from prices in the config, which only the CostCalculator bean knows;
     * it is passed in, so the record stays free of Spring and a test can pass its own prices.
     */
    public static LlmCallMetrics from(ChatResponse response, CostCalculator costCalculator, int latencyMs) {
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        return new LlmCallMetrics(
                metadata.getModel(),  // the model that actually answered, may carry a date suffix
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                reasoningTokens(usage),
                costCalculator.cost(metadata.getModel(), usage.getPromptTokens(), usage.getCompletionTokens()),
                latencyMs
        );
    }

    // Reasoning tokens are not part of the generic Usage; OpenAI reports them in its own usage object
    private static Integer reasoningTokens(Usage usage) {
        if (usage.getNativeUsage() instanceof CompletionUsage openAiUsage) {
            return openAiUsage.completionTokensDetails()
                    .flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens)
                    .map(Long::intValue)
                    .orElse(null);
        }
        return null;
    }
}
