package dev.kyrylo.ticket_triage.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record OrderSummary (
        String orderNumber,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        LocalDate eta,
        Instant createdAt
) {
    static OrderSummary from(Order order) {
        return new OrderSummary(
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getEta(),
                order.getCreatedAt()
        );
    }
}
