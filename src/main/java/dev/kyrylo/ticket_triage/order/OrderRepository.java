package dev.kyrylo.ticket_triage.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByOrderNumberIgnoreCase(String orderNumber);

    List<Order> findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    Optional<Order> findByOrderNumberIgnoreCaseAndCustomerEmailIgnoreCase(String orderNumber, String customerEmail);
}
