package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.common.LlmMetrics;
import dev.kyrylo.ticket_triage.customer.CustomerRepository;
import dev.kyrylo.ticket_triage.reply.TicketDraftReply;
import dev.kyrylo.ticket_triage.reply.TicketDraftReplyRepository;
import dev.kyrylo.ticket_triage.reply.TicketDraftReplyService;
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
    private final TicketDraftReplyService ticketDraftReplyService;
    private final TicketDraftReplyRepository ticketDraftReplyRepository;
    private final CustomerRepository customerRepository;
    private final TicketTriageService ticketTriageService;
    private final Clock clock;
    private final LlmMetrics llmMetrics;

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
            llmMetrics.recordSuccess("triage", "triaged");
        } catch (Exception e) {
            log.warn("Triage failed for ticket {}", savedTicket.getId(), e);
            savedTicket.markTriageFailed();
            llmMetrics.recordFailure("triage", e);
        }

        // Persist the status before the draft call: the draft is optional and may be slow or fail
        savedTicket = ticketRepository.save(savedTicket);

        TicketDraftReply draftReply = null;
        if (triage != null) {
            try {
                draftReply = ticketDraftReplyService.draftReply(savedTicket, triage);
                llmMetrics.recordSuccess("draft", "drafted");
            } catch (Exception e) {
                log.warn("Creating draft reply failed for ticket {}", savedTicket.getId(), e);
                llmMetrics.recordFailure("draft", e);
            }
        }

        return new TicketDetails(savedTicket, triage, draftReply);
    }

    public TicketDetails get(UUID id) {
        Ticket ticket = ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
        TicketTriage triage = ticketTriageRepository.findById(id).orElse(null);
        TicketDraftReply draftReply = ticketDraftReplyRepository.findById(id).orElse(null);

        return new TicketDetails(ticket, triage, draftReply);
    }

    @Transactional(readOnly = true)
    public List<TicketDetails> list(TicketStatus status) {
        List<Ticket> tickets = status == null
                ? ticketRepository.findAllByOrderByCreatedAtDesc()
                : ticketRepository.findByStatusOrderByCreatedAtDesc(status);

        List<UUID> ids = tickets.stream().map(Ticket::getId).toList();
        Map<UUID, TicketTriage> triages = ticketTriageRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(TicketTriage::getTicketId, t -> t));
        Map<UUID, TicketDraftReply> draftReplies = ticketDraftReplyRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(TicketDraftReply::getTicketId, d -> d));

        return tickets.stream()
                .map(t -> new TicketDetails(t, triages.get(t.getId()), draftReplies.get(t.getId())))
                .toList();
    }
}
