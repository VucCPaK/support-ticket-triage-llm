package dev.kyrylo.ticket_triage.ticket;

import dev.kyrylo.ticket_triage.customer.CustomerRepository;
import dev.kyrylo.ticket_triage.llm.BudgetGuard;
import dev.kyrylo.ticket_triage.llm.LlmMetrics;
import dev.kyrylo.ticket_triage.reply.TicketDraftReply;
import dev.kyrylo.ticket_triage.reply.TicketDraftReplyService;
import dev.kyrylo.ticket_triage.triage.TicketTriage;
import dev.kyrylo.ticket_triage.triage.TicketTriageService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The AI pipeline for one saved ticket: triage, then the optional draft reply.
 * Each step checks the daily budget, records its outcome, and never lets a failure escape:
 * the ticket is kept either way and its status says what happened.
 */
@Slf4j
@Component
public class TicketProcessor {
    private final TicketRepository ticketRepository;
    private final CustomerRepository customerRepository;
    private final TicketTriageService ticketTriageService;
    private final TicketDraftReplyService ticketDraftReplyService;
    private final BudgetGuard budgetGuard;
    private final LlmMetrics llmMetrics;

    public TicketProcessor(TicketRepository ticketRepository, CustomerRepository customerRepository,
                           TicketTriageService ticketTriageService, TicketDraftReplyService ticketDraftReplyService,
                           BudgetGuard budgetGuard, LlmMetrics llmMetrics) {
        this.ticketRepository = ticketRepository;
        this.customerRepository = customerRepository;
        this.ticketTriageService = ticketTriageService;
        this.ticketDraftReplyService = ticketDraftReplyService;
        this.budgetGuard = budgetGuard;
        this.llmMetrics = llmMetrics;
    }

    public TicketDetails process(Ticket ticket) {
        TicketTriage triage = runTriage(ticket);
        // Persist the status before the draft call: the draft is optional and may be slow or fail
        Ticket saved = ticketRepository.save(ticket);
        TicketDraftReply draftReply = triage == null ? null : runDraft(saved, triage);
        return new TicketDetails(saved, triage, draftReply);
    }

    private @Nullable TicketTriage runTriage(Ticket ticket) {
        // Checked before the call: its cost is known only afterwards, and a paid answer is never thrown away
        if (budgetGuard.isExhausted()) {
            log.warn("Daily LLM budget is used up, ticket {} is saved without triage", ticket.getId());
            ticket.markTriageSkipped();
            llmMetrics.recordSkipped("triage", "budget");
            return null;
        }
        try {
            TicketTriage triage = ticketTriageService.triageTicket(customerPlan(ticket), ticket);
            ticket.markTriaged();
            llmMetrics.recordSuccess("triage", "triaged");
            return triage;
        } catch (Exception e) {
            log.warn("Triage failed for ticket {}", ticket.getId(), e);
            ticket.markTriageFailed();
            llmMetrics.recordFailure("triage", e);
            return null;
        }
    }

    private @Nullable TicketDraftReply runDraft(Ticket ticket, TicketTriage triage) {
        // The triage call may have just crossed the cap; the draft is optional, so it is the one to skip
        if (budgetGuard.isExhausted()) {
            llmMetrics.recordSkipped("draft", "budget");
            return null;
        }
        try {
            TicketDraftReply draftReply = ticketDraftReplyService.draftReply(ticket, triage);
            llmMetrics.recordSuccess("draft", "drafted");
            return draftReply;
        } catch (Exception e) {
            log.warn("Creating draft reply failed for ticket {}", ticket.getId(), e);
            llmMetrics.recordFailure("draft", e);
            return null;
        }
    }

    private String customerPlan(Ticket ticket) {
        return customerRepository.findByEmailIgnoreCase(ticket.getCustomerEmail())
                .map(customer -> customer.getPlan().name())
                .orElse("UNKNOWN");
    }
}
