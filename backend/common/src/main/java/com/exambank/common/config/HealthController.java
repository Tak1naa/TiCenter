package com.exambank.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** 统一健康检查端点（三 Java 服务复用，ApiAuthFilter 已放行 /health）。 */
@RestController
public class HealthController {

    @Value("${spring.application.name:unknown}")
    private String serviceName;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", serviceName,
                "time", Instant.now().toString()
        );
    }
}
