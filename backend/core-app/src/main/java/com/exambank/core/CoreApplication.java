package com.exambank.core;

import com.exambank.chat.ChatServiceApplication;
import com.exambank.document.DocumentServiceApplication;
import com.exambank.vector.VectorServiceApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = "com.exambank", excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {ChatServiceApplication.class, DocumentServiceApplication.class, VectorServiceApplication.class}))
@EntityScan("com.exambank.common.entity")
@EnableJpaRepositories("com.exambank.common.repository")
@EnableAsync
@EnableScheduling
public class CoreApplication {
    public static void main(String[] args) { SpringApplication.run(CoreApplication.class, args); }
}
