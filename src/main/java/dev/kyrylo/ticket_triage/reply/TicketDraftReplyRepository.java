package dev.kyrylo.ticket_triage.reply;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TicketDraftReplyRepository extends JpaRepository<TicketDraftReply, UUID> {

}
