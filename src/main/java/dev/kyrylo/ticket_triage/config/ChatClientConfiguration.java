package dev.kyrylo.ticket_triage.config;

import lombok.Getter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class ChatClientConfiguration {
    @Bean("triageChatClient")
    public ChatClient triageChatClient(
            ChatClient.Builder builder,
            @Value("classpath:prompts/triage-system-v${llm.triage.prompt-version}.st") Resource systemPromptResource) {

        return builder.defaultSystem(systemPromptResource).build();
    }
}
