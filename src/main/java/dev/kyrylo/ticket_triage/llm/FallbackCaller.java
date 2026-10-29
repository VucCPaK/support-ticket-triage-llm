package dev.kyrylo.ticket_triage.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Calls the main model and switches to the fallback when the provider is unavailable.
 * Any step can use it by passing two calls, so the switching rule lives in one place.
 */
@Slf4j
@Component
public class FallbackCaller {
    private final LlmMetrics llmMetrics;

    public FallbackCaller(LlmMetrics llmMetrics) {
        this.llmMetrics = llmMetrics;
    }

    public <T> T call(String step, Supplier<T> mainCall, Supplier<T> fallbackCall) {
        try {
            return mainCall.get();
        } catch (RuntimeException e) {
            if (!ModelErrors.isProviderUnavailable(e)) {
                throw e;  // our request is wrong: the fallback model would fail the same way
            }
            log.warn("Main model failed for step {}, trying the fallback model", step, e);
            llmMetrics.recordFallback(step, e);
            return fallbackCall.get();
        }
    }
}
