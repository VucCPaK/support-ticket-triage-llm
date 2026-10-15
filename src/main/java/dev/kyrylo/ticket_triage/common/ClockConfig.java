package dev.kyrylo.ticket_triage.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class ClockConfig {

    // Injected instead of calling Instant.now() directly, so tests can use a fixed clock
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
