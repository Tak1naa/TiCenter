package com.exambank.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class SecurityConfig {
    @Bean UserDetailsService userDetailsService() { return new InMemoryUserDetailsManager(); }
    @Bean ApiAuthFilter apiAuthFilter() { return new ApiAuthFilter(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiAuthFilter apiAuthFilter) throws Exception {
        return http
                // ApiAuthFilter performs double-submit CSRF only for cookie-authenticated unsafe methods.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/auth/login", "/api/student/login", "/api/student/register",
                                "/api/student/forgot-password", "/api/student/reset-password",
                                "/health", "/actuator/health/**", "/actuator/prometheus", "/actuator/metrics/**").permitAll()
                        .requestMatchers("/api/admin/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "EDITOR", "VIEWER")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(apiAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
