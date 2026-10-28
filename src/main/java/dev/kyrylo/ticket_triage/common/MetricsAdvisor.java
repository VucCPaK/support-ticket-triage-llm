package dev.kyrylo.ticket_triage.common;

import dev.kyrylo.ticket_triage.triage.LlmCallMetrics;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

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

        if (response.chatResponse() != null) {
            LlmCallMetrics llmCallMetrics = LlmCallMetrics.from(response.chatResponse(), costCalculator, latencyMs);
            llmMetrics.recordCall(step, llmCallMetrics);
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
