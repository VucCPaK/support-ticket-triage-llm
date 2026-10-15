package dev.kyrylo.ticket_triage.common;

import dev.kyrylo.ticket_triage.ticket.TicketNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC 9457 ProblemDetail responses.
 * Validation and other framework errors are handled by Spring Boot (spring.mvc.problemdetails.enabled).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TicketNotFoundException.class)
    ProblemDetail handleTicketNotFound(TicketNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Ticket not found");
        return problem;
    }
}
