package com.mariozechner.pi.codingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Pi-Coding-Agent - Spring Boot CLI application.
 */
@SpringBootApplication(scanBasePackages = "com.mariozechner.pi")
public class PiCodingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PiCodingAgentApplication.class, args);
    }
}
