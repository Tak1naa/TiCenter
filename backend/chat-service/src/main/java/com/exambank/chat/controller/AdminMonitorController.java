package com.exambank.chat.controller;

import com.exambank.chat.service.MonitorStatsService;
import com.exambank.common.repository.ExamFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 监控聚合：探测 6 个服务健康 + 错误统计 + 最近事件（admin 端监控中心数据源）。
 */
@RestController
public class AdminMonitorController {

    private static final Logger log = LoggerFactory.getLogger(AdminMonitorController.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final MonitorStatsService statsService;
    private final ExamFileRepository examFileRepository;
    private final WebClient webClient;

    @Value("${app.ocr.service-url:http://localhost:8001}")
    private String ocrUrl;
    @Value("${app.embedding.service-url:http://localhost:8002}")
    private String embeddingUrl;
    @Value("${app.llm.gateway-url:http://localhost:8003}")
    private String llmUrl;
    @Value("${app.document.service-url:http://localhost:39181}")
    private String documentUrl;
    @Value("${app.vector.service-url:http://localhost:39182}")
    private String vectorUrl;

    public AdminMonitorController(MonitorStatsService statsService,
                                  ExamFileRepository examFileRepository) {
        this.statsService = statsService;
        this.examFileRepository = examFileRepository;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1 * 1024 * 1024))
                .build();
    }

    @GetMapping("/api/admin/monitor/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = "X-Admin-Token", defaultValue = "") String token) {
        if (AdminAuthController.checkSession(token) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }

        // 探测 6 服务（自身 chat 算 up；远端 5 个并行探测，最多 2s 而不是 10s）
        List<Map<String, Object>> services = java.util.Collections.synchronizedList(new ArrayList<>());
        services.add(Map.of("name", "chat-service", "url", "self", "up", true, "latencyMs", 0L));
        List<String[]> targets = List.of(
                new String[]{"document-service", documentUrl + "/health"},
                new String[]{"vector-service", vectorUrl + "/health"},
                new String[]{"ocr-service", ocrUrl + "/health"},
                new String[]{"embedding-service", embeddingUrl + "/health"},
                new String[]{"llm-gateway", llmUrl + "/health"});
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
            for (String[] t : targets) {
                futures.add(java.util.concurrent.CompletableFuture.runAsync(
                        () -> services.add(probe(t[0], t[1], false)), executor));
            }
            java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        }

        // 解析失败统计（24h 内，SQL 聚合而非全表加载）
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        long parseFailures = examFileRepository.countByParseStatusAndUpdatedAtAfter("failed", cutoff);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("services", services);
        result.put("stats", Map.of(
                "chatFailures", statsService.chatFailures(),
                "parseFailures", parseFailures,
                "loginFailures", statsService.loginFailures()));
        result.put("recentEvents", statsService.recentEvents());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> probe(String name, String url, boolean isSelf) {
        long start = System.currentTimeMillis();
        boolean up = false;
        try {
            if (isSelf) {
                up = true;
            } else {
                String resp = webClient.get().uri(url).retrieve()
                        .bodyToMono(String.class).timeout(TIMEOUT).block();
                up = resp != null && resp.contains("\"ok\"");
            }
        } catch (Exception e) {
            up = false;
        }
        long latency = System.currentTimeMillis() - start;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("url", isSelf ? "self" : url);
        m.put("up", up);
        m.put("latencyMs", latency);
        return m;
    }
}
