package dev.kyrylo.ticket_triage.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
    }

    public void markTriageFailed() {
        requireStatus(TicketStatus.NEW);
        this.status = TicketStatus.TRIAGE_FAILED;
    }

    private void requireStatus(TicketStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Ticket " + id + " is " + status + ", expected " + expected);
        }
    }
}
