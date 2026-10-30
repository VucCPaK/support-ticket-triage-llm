package dev.kyrylo.ticket_triage.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.time.Duration;

@Configuration
public class ChatClientConfiguration {
    private final Duration requestTimeout;

    /**
     * Spring AI 2.0.1 sends every chat request with its own timeout from OpenAiChatOptions (60s by default),
     * which overrides spring.ai.openai.timeout on the HTTP client. Found by OpenAiProtocolTest: a 4s answer
     * was awaited with a 2s timeout. So the same setting is put into the options of every client here,
     * and the recovery lease check computes the worst case from the value that really applies.
     */
    public ChatClientConfiguration(@Value("${spring.ai.openai.timeout}") Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Bean("triageChatClient")
    public ChatClient triageChatClient(
            ChatClient.Builder builder,
            CostCalculator costCalculator,
            LlmMetrics llmMetrics,
            @Value("classpath:prompts/triage-system-v${llm.triage.prompt-version}.st") Resource systemPromptResource,
            // Empty means the model's own default; set it to compare speed and cost against quality in the eval
            @Value("${llm.triage.reasoning-effort:}") String reasoningEffort) {

        OpenAiChatOptions.Builder options = options();
        if (!reasoningEffort.isBlank()) {
            options.reasoningEffort(reasoningEffort);
        }
        return builder
                .defaultSystem(systemPromptResource)
                .defaultAdvisors(new MetricsAdvisor("triage", costCalculator, llmMetrics))
                .defaultOptions(options)
                .build();
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
                .defaultOptions(options().model(llmProperties.fallback().model()))
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
                .defaultOptions(options().reasoningEffort("none"))
                .build();
    }

    private OpenAiChatOptions.Builder options() {
        return OpenAiChatOptions.builder().timeout(requestTimeout);
    }
}
