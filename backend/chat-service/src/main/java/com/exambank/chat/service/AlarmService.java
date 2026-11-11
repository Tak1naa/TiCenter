package com.exambank.chat.service;

import com.exambank.common.entity.ExamFile;
import com.exambank.common.repository.ExamFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警轮询：每分钟检查服务健康/解析失败/连续聊天失败，触发邮件告警。
 * 去重限频：同类告警 30 分钟内最多一封；服务恢复后解除限频可再次告警。
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final long ALARM_INTERVAL_MS = 30 * 60 * 1000;

    private final MonitorStatsService statsService;
    private final ExamFileRepository examFileRepository;
    private final MailAlarmService mailAlarmService;
    private final WebClient webClient = WebClient.builder().build();

    @org.springframework.beans.factory.annotation.Value("${app.ocr.service-url:http://localhost:8001}")
    private String ocrUrl;
    @org.springframework.beans.factory.annotation.Value("${app.embedding.service-url:http://localhost:8002}")
    private String embeddingUrl;
    @org.springframework.beans.factory.annotation.Value("${app.llm.gateway-url:http://localhost:8003}")
    private String llmUrl;
    @org.springframework.beans.factory.annotation.Value("${app.document.service-url:http://localhost:39181}")
    private String documentUrl;
    @org.springframework.beans.factory.annotation.Value("${app.vector.service-url:http://localhost:39182}")
    private String vectorUrl;

    /** key=类型:目标 → 上次告警时间戳 */
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    public AlarmService(MonitorStatsService statsService,
                        ExamFileRepository examFileRepository,
                        MailAlarmService mailAlarmService) {
        this.statsService = statsService;
        this.examFileRepository = examFileRepository;
        this.mailAlarmService = mailAlarmService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        try {
            checkServices();
            checkParseFailures();
            checkChatFailures();
        } catch (Exception e) {
            log.error("Alarm tick failed", e);
        }
    }

    private void checkServices() {
        String[] targets = {
                "document-service:" + documentUrl + "/health",
                "vector-service:" + vectorUrl + "/health",
                "ocr-service:" + ocrUrl + "/health",
                "embedding-service:" + embeddingUrl + "/health",
                "llm-gateway:" + llmUrl + "/health",
        };
        for (String t : targets) {
            String[] parts = t.split(":", 2);
            String name = parts[0], url = parts[1];
            boolean up = isUp(url);
            if (up) {
                lastSent.remove("svc:" + name); // 恢复后允许再次告警
            } else {
                maybeAlarm("svc:" + name, "服务下线",
                        "服务 " + name + " 不可用\n地址: " + url + "\n请检查进程状态。");
            }
        }
    }

    private void checkParseFailures() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        long failed = examFileRepository.countByParseStatusAndUpdatedAtAfter("failed", cutoff);
        if (failed > 0) {
            maybeAlarm("parse", "文件解析失败",
                    "最近 1 小时内有 " + failed + " 个文件解析失败。\n请到 admin 文件管理查看详情。");
        } else {
            lastSent.remove("parse");
        }
    }

    private void checkChatFailures() {
        int drained = statsService.drainChatFailures();
        if (drained >= 3) {
            maybeAlarm("chat", "连续聊天失败",
                    "最近 1 分钟内有 " + drained + " 次聊天失败。\n可能原因：AI 供应商不可用 / key 失效。");
        }
    }

    private boolean isUp(String url) {
        try {
            String resp = webClient.get().uri(url).retrieve()
                    .bodyToMono(String.class).timeout(TIMEOUT).block();
            return resp != null && resp.contains("\"ok\"");
        } catch (Exception e) {
            return false;
        }
    }

    private void maybeAlarm(String key, String title, String body) {
        long now = System.currentTimeMillis();
        Long last = lastSent.get(key);
        if (last != null && now - last < ALARM_INTERVAL_MS) return; // 限频
        lastSent.put(key, now);
        String subject = "【AI题库告警】" + title;
        log.warn("[ALARM] {} | {}", subject, body.replace('\n', ' '));
        mailAlarmService.sendAlarm(subject, body);
    }
}
