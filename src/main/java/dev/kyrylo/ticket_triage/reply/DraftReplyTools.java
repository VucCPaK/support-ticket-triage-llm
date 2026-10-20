package dev.kyrylo.ticket_triage.reply;

import dev.kyrylo.ticket_triage.order.OrderService;
import dev.kyrylo.ticket_triage.order.OrderSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class DraftReplyTools {
    private final OrderService orderService;

    @Tool(description = "Returns the customer's 5 most recent orders, newest first")
    public List<OrderSummary> getRecentOrders(ToolContext toolContext) {
        if (!(toolContext.getContext().get("customerEmail") instanceof String email)) {
            throw new IllegalStateException("customerEmail is missing in tool context");
        }
        if (toolContext.getContext().get("toolCalls") instanceof AtomicInteger counter) {
            counter.incrementAndGet();
        }
        return orderService.lastFiveRecentOrders(email);
    }
}
