package io.pilots.processservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

/**
 * Process Sharing API — mediates between the EDC connector and internal enterprise applications.
 * An embedded Flowable BPMN engine manages service instance state.
 *
 * Security is excluded from auto-configuration here; add a proper SecurityFilterChain
 * bean when authentication/authorisation is implemented.
 */
@SpringBootApplication(exclude = {SecurityAutoConfiguration.class})
public class ProcessServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessServiceApplication.class, args);
    }
}
