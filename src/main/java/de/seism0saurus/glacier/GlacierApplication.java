package de.seism0saurus.glacier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The GlacierApplication class is the main class for running the Glacier application.
 * It is annotated with the @SpringBootApplication annotation to enable Spring Boot features and configuration.
 * The @EnableScheduling annotation is used to enable scheduling support in the application.
 * It contains a main method that starts the application.
 */
@SpringBootApplication
public class GlacierApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlacierApplication.class, args);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/rest/*").allowedOrigins("http://localhost:4200");
            }
        };
    }

    @Bean
    public RestTemplate restTemplate(){
        return new RestTemplate();
    }
}
