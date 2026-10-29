package dev.kyrylo.ticket_triage.common;

import dev.kyrylo.ticket_triage.triage.LlmCallMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

@Slf4j
public class MetricsAdvisor implements CallAdvisor {
    private final String step;
    private final CostCalculator costCalculator;
    private final LlmMetrics llmMetrics;

    public MetricsAdvisor(String step, CostCalculator costCalculator, LlmMetrics llmMetrics) {
        this.step = step;
        this.costCalculator = costCalculator;
        this.llmMetrics = llmMetrics;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse != null) {
            try {
                llmMetrics.recordCall(step, LlmCallMetrics.from(chatResponse, costCalculator, latencyMs));
            } catch (RuntimeException e) {
                // Metrics must never break a call the model already answered (and that we already paid for)
                log.warn("Could not record LLM metrics for step {}", step, e);
            }
        }

        return response;
    }

    @Override
    public String getName() {
        return "MetricsAdvisor-" + step;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
