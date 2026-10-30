package dev.kyrylo.ticket_triage.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
@Getter
public class Ticket {

    @Id
    private UUID id;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Written by the recovery job's claim query; the entity only reads them and records the last error
    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    // A stale save (another worker re-claimed the ticket meanwhile) fails instead of overwriting newer data.
    // Long, not long: null tells Spring Data the ticket is new, since the id is assigned in open()
    @Version
    private Long version;

    protected Ticket() {
        // for JPA
    }

    private Ticket(String customerEmail, String subject, String body, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.customerEmail = customerEmail;
        this.subject = subject;
        this.body = body;
        this.status = TicketStatus.NEW;
        this.createdAt = createdAt;
    }

    public static Ticket open(String customerEmail, String subject, String body, Instant now) {
        return new Ticket(customerEmail, subject, body, now);
    }

    public void markTriaged() {
        requireStatus(TicketStatus.NEW);
        this.status = TicketStatus.TRIAGED;
        this.lastError = null;
    }

    public void markTriageFailed(String error) {
        requireStatus(TicketStatus.NEW);
        this.status = TicketStatus.TRIAGE_FAILED;
        this.lastError = error.length() > 500 ? error.substring(0, 500) : error;
    }

    /** The daily LLM budget is used up: the ticket waits for a human instead of the model. */
    public void markTriageSkipped() {
        requireStatus(TicketStatus.NEW);
        this.status = TicketStatus.TRIAGE_SKIPPED;
    }

    private void requireStatus(TicketStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Ticket " + id + " is " + status + ", expected " + expected);
        }
    }
}
