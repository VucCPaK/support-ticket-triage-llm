package dev.kyrylo.ticket_triage.common;

import dev.kyrylo.ticket_triage.triage.LlmCallMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Business-level LLM metrics on top of what Spring AI already publishes (gen_ai.client.*):
 * whether a step succeeded, and what each call cost.
 */
@Component
public class LlmMetrics {

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One model call that returned an answer: latency, cost and tokens per pipeline step. */
    public void recordCall(String step, LlmCallMetrics call) {
        Timer.builder("llm.latency")
                .description("Latency of one LLM call, including tool-calling round trips")
                .tag("step", step)
                .publishPercentiles(0.5, 0.95)
                .register(registry)
                .record(Duration.ofMillis(call.latencyMs()));
        DistributionSummary.builder("llm.cost")
                .description("Cost of one LLM call")
                .baseUnit("usd")
                .tag("step", step)
                .register(registry)
                .record(call.costUsd().doubleValue());
        Counter.builder("llm.tokens").tag("step", step).tag("type", "input")
                .register(registry).increment(call.inputTokens());
        Counter.builder("llm.tokens").tag("step", step).tag("type", "output")
                .register(registry).increment(call.outputTokens());
    }

    public void recordSuccess(String step, String outcome) {
        outcome(step, outcome, "none");
    }

    /**
     * A step that failed. The reason is the exception class (RateLimitException, BadRequestException...),
     * a small fixed set, so it is safe as a tag; the message is not.
     */
    public void recordFailure(String step, Exception e) {
        outcome(step, "failed", e.getClass().getSimpleName());
    }

    private void outcome(String step, String outcome, String reason) {
        Counter.builder("ticket." + step)
                .description("How the " + step + " step of a new ticket ended")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
