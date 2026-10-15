package dev.kyrylo.ticket_triage;

import org.springframework.boot.SpringApplication;

public class TestTicketTriageApplication {

	public static void main(String[] args) {
		SpringApplication.from(TicketTriageApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
