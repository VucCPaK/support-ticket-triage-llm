package dev.kyrylo.ticket_triage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TicketTriageApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketTriageApplication.class, args);
	}

}
