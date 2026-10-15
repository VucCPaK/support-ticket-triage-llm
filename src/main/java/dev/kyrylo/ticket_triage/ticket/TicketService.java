package dev.kyrylo.ticket_triage.ticket;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TicketService {

    private final TicketRepository tickets;
    private final Clock clock;

    public TicketService(TicketRepository tickets, Clock clock) {
        this.tickets = tickets;
        this.clock = clock;
    }

    public Ticket create(CreateTicketRequest request) {
        Ticket ticket = Ticket.open(
                request.customerEmail().trim(),
                request.subject().trim(),
                request.body().trim(),
                Instant.now(clock)
        );
        return tickets.save(ticket);
    }

    public Ticket get(UUID id) {
        return tickets.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Ticket> list(TicketStatus status) {
        return status == null
                ? tickets.findAllByOrderByCreatedAtDesc()
                : tickets.findByStatusOrderByCreatedAtDesc(status);
    }
}
