package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.customer.CustomerRepository;
import dev.kyrylo.ticket_triage.triage.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
@Slf4j
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketTriageRepository ticketTriageRepository;
    private final CustomerRepository customerRepository;
    private final TicketTriageService ticketTriageService;
    private final Clock clock;

    public TicketDetails create(CreateTicketRequest request) {
        Ticket ticket = Ticket.open(
                request.customerEmail().trim(),
                request.subject().trim(),
                request.body().trim(),
                Instant.now(clock)
        );
        Ticket savedTicket = ticketRepository.save(ticket);

        String customerPlan = customerRepository.findByEmailIgnoreCase(request.customerEmail().trim())
                .map(c -> c.getPlan().name())
                .orElse("UNKNOWN");

        TicketTriage triage = null;
        try {
            triage = ticketTriageService.triageTicket(customerPlan, savedTicket);
            savedTicket.markTriaged();
        } catch (Exception e) {
            log.warn("Triage failed for ticket {}", savedTicket.getId(), e);
            savedTicket.markTriageFailed();
        }
        return TicketDetails.from(ticketRepository.save(savedTicket), triage);
    }

    public TicketDetails get(UUID id) {
        Ticket ticket = ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
        TicketTriage triage = ticketTriageRepository.findById(id).orElse(null);

        return TicketDetails.from(ticket, triage);
    }

    @Transactional(readOnly = true)
    public List<TicketDetails> list(TicketStatus status) {
        List<Ticket> tickets = status == null
                ? ticketRepository.findAllByOrderByCreatedAtDesc()
                : ticketRepository.findByStatusOrderByCreatedAtDesc(status);

        Map<UUID, TicketTriage> triages = ticketTriageRepository
                .findAllById(tickets.stream().map(Ticket::getId).toList()).stream()
                .collect(Collectors.toMap(TicketTriage::getTicketId, t -> t));

        return tickets.stream()
                .map(t -> TicketDetails.from(t, triages.get(t.getId())))
                .toList();
    }
}
