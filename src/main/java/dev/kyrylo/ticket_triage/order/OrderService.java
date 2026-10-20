package dev.kyrylo.ticket_triage.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}
