package dev.kyrylo.ticket_triage.triage;

import com.openai.models.completions.CompletionUsage;
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

    public static LlmCallMetrics from(ChatResponse response, BigDecimal costUsd, int latencyMs) {
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        return new LlmCallMetrics(
                metadata.getModel(),  // the model that actually answered, may carry a date suffix
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                reasoningTokens(usage),
                costUsd,
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
