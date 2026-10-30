package dev.kyrylo.ticket_triage;

import dev.kyrylo.ticket_triage.reply.TicketDraftReply;
import dev.kyrylo.ticket_triage.reply.TicketDraftReplyRepository;
import dev.kyrylo.ticket_triage.ticket.CreateTicketRequest;
import dev.kyrylo.ticket_triage.ticket.TicketDetails;
import dev.kyrylo.ticket_triage.ticket.TicketService;
import dev.kyrylo.ticket_triage.ticket.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 3: the real model, a few paid calls. Excluded from the normal build (pom: test.excluded.groups=live);
 * run on a schedule, after a Spring AI or model upgrade, or before a deploy:
 *
 *     ./mvnw test -Dgroups=live -Dtest.excluded.groups=none
 *
 * Checks that the integration with the provider works, never the exact text: answers are not deterministic.
 * Quality is the eval's job. Example of what only this level catches: the 400 "function tools with
 * reasoning_effort are not supported", which no fake would ever return.
 */
@Tag("live")
@SpringBootTest(properties = "tickets.recovery.enabled=false")
@Import(TestcontainersConfiguration.class)
class LiveModelTest {

    @Autowired
    TicketService ticketService;

    @Autowired
    TicketDraftReplyRepository ticketDraftReplyRepository;

    @Test
    void ticketWithAnOrderNumberIsTriagedAndDrafted() {
        TicketDetails details = ticketService.create(new CreateTicketRequest(
                "anna.murphy@example.com", "Where is my order?",
                "My order A123 was supposed to arrive on Monday and it is still not here."));

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(details.triage().getCategory()).isNotNull();
        assertThat(details.triage().getOrderNumber()).isEqualTo("A123");
        assertThat(details.draftReply()).isNotNull();
        assertThat(details.draftReply().getReplyText()).isNotBlank();
    }

    @Test
    void draftWithoutAnOrderNumberWorksThroughTheToolCall() {
        // No number: the draft model calls getRecentOrders. Tools plus reasoning failed once with a 400.
        TicketDetails details = ticketService.create(new CreateTicketRequest(
                "liam.byrne@example.com", "Question", "I have a question about my order."));

        assertThat(details.ticket().getStatus()).isEqualTo(TicketStatus.TRIAGED);
        TicketDraftReply draft = ticketDraftReplyRepository.findById(details.ticket().getId()).orElseThrow();
        assertThat(draft.getReplyText()).isNotBlank();
    }
}
