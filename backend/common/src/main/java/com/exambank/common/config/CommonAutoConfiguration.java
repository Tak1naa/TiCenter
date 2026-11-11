package com.exambank.common.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EntityScan(basePackages = "com.exambank.common.entity")
@EnableJpaRepositories(basePackages = "com.exambank.common.repository")
@EnableCaching
@EnableMethodSecurity
public class CommonAutoConfiguration {
}
