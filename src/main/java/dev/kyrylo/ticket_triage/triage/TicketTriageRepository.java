package dev.kyrylo.ticket_triage.triage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TicketTriageRepository extends JpaRepository<TicketTriage, UUID> {

}
