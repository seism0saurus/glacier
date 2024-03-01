package de.seism0saurus.glacier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The GlacierApplication class is the main class for running the Glacier application.
 * It is annotated with the @SpringBootApplication annotation to enable Spring Boot features and configuration.
 * The @EnableScheduling annotation is used to enable scheduling support in the application.
 * It contains a main method that starts the application.
 */
@SpringBootApplication
@EnableScheduling
public class GlacierApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlacierApplication.class, args);
    }
}
