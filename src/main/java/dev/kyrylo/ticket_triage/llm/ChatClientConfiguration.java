package dev.kyrylo.ticket_triage.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class ChatClientConfiguration {
    @Bean("triageChatClient")
    public ChatClient triageChatClient(
            ChatClient.Builder builder,
            CostCalculator costCalculator,
            LlmMetrics llmMetrics,
            @Value("classpath:prompts/triage-system-v${llm.triage.prompt-version}.st") Resource systemPromptResource,
            // Empty means the model's own default; set it to compare speed and cost against quality in the eval
            @Value("${llm.triage.reasoning-effort:}") String reasoningEffort) {

        builder
                .defaultSystem(systemPromptResource)
                .defaultAdvisors(new MetricsAdvisor("triage", costCalculator, llmMetrics));
        if (!reasoningEffort.isBlank()) {
            builder.defaultOptions(OpenAiChatOptions.builder().reasoningEffort(reasoningEffort));
        }
        return builder.build();
    }

    /**
     * Same prompt and metrics as triageChatClient, another model. Used only when the main model fails
     * with a rate limit, a server error or a timeout. Not run through the eval: an accepted risk,
     * a rough triage beats a ticket with none while the main model is down.
     */
    @Bean("triageFallbackChatClient")
    public ChatClient triageFallbackChatClient(
            ChatClient.Builder builder,
            CostCalculator costCalculator,
            LlmMetrics llmMetrics,
            LlmProperties llmProperties,
            @Value("classpath:prompts/triage-system-v${llm.triage.prompt-version}.st") Resource systemPromptResource) {

        return builder
                .defaultSystem(systemPromptResource)
                .defaultAdvisors(new MetricsAdvisor("triage", costCalculator, llmMetrics))
                .defaultOptions(OpenAiChatOptions.builder().model(llmProperties.fallback().model()))
                .build();
    }

    @Bean("draftReplyChatClient")
    public ChatClient draftReplyChatClient(
            ChatClient.Builder builder,
            CostCalculator costCalculator,
            LlmMetrics llmMetrics,
            @Value("classpath:prompts/draft-reply-v${llm.draft.reply-version}.st") Resource systemPromptResource) {

        // gpt-6-luna rejects function tools combined with reasoning in /v1/chat/completions,
        // which is the API Spring AI uses. The draft does not need reasoning, so it is turned off here only.
        return builder
                .defaultSystem(systemPromptResource)
                .defaultAdvisors(new MetricsAdvisor("draft", costCalculator, llmMetrics))
                .defaultOptions(OpenAiChatOptions.builder().reasoningEffort("none"))
                .build();
    }
}
