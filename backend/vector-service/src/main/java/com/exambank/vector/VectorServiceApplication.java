package com.exambank.vector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.exambank")
public class VectorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VectorServiceApplication.class, args);
    }
}
