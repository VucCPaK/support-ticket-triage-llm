package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.reply.TicketDraftReply;
import dev.kyrylo.ticket_triage.reply.TicketDraftReplyRepository;
import dev.kyrylo.ticket_triage.triage.TicketTriage;
import dev.kyrylo.ticket_triage.triage.TicketTriageRepository;
import lombok.AllArgsConstructor;
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
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketTriageRepository ticketTriageRepository;
    private final TicketDraftReplyRepository ticketDraftReplyRepository;
    private final TicketProcessor ticketProcessor;
    private final Clock clock;

    public TicketDetails create(CreateTicketRequest request) {
        Ticket ticket = Ticket.open(
                request.customerEmail().trim(),
                request.subject().trim(),
                request.body().trim(),
                Instant.now(clock)
        );
        return ticketProcessor.process(ticketRepository.save(ticket));
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
