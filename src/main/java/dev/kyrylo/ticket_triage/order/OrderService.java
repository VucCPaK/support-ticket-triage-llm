package dev.kyrylo.ticket_triage.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;

    public Optional<OrderSummary> findCustomerOrder(String orderNumber, String customerEmail) {
        if (orderNumber == null || orderNumber.isBlank() || customerEmail == null || customerEmail.isBlank()) {
            return Optional.empty();
        }

        Optional<Order> order = orderRepository.findByOrderNumberIgnoreCaseAndCustomerEmailIgnoreCase(orderNumber, customerEmail);
        return order.map(OrderSummary::from);
    }

    public List<OrderSummary> lastFiveRecentOrders(String customerEmail) {
        if (customerEmail == null || customerEmail.isBlank()) {
            return new ArrayList<>();
        }

        List<Order> lastFiveRecentOrders =
                orderRepository.findFirst5ByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(customerEmail);

        return OrderSummary.from(lastFiveRecentOrders);
    }
}
