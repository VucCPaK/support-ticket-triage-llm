package dev.kyrylo.ticket_triage.config;

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
            @Value("classpath:prompts/triage-system-v${llm.triage.prompt-version}.st") Resource systemPromptResource) {

        return builder.defaultSystem(systemPromptResource).build();
    }

    @Bean("draftReplyChatClient")
    public ChatClient draftReplyChatClient(
            ChatClient.Builder builder,
            @Value("classpath:prompts/draft-reply-v${llm.draft.reply-version}.st") Resource systemPromptResource) {

        // gpt-6-luna rejects function tools combined with reasoning in /v1/chat/completions,
        // which is the API Spring AI uses. The draft does not need reasoning, so it is turned off here only.
        return builder
                .defaultSystem(systemPromptResource)
                .defaultOptions(OpenAiChatOptions.builder().reasoningEffort("none"))
                .build();
    }
}
