package com.enterprise.apidrift;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApiDriftApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiDriftApplication.class, args);
    }
}
