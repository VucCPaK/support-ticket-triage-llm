package dev.kyrylo.ticket_triage.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FakeChatModelConfiguration {

    // @Primary: the auto-configured ChatClient.Builder takes this model instead of the OpenAI one
    @Bean
    @Primary
    FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }
}
