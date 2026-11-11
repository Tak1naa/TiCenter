package com.exambank.chat.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 监控统计：聊天/登录失败计数 + 最近事件环形队列（内存，重启归零，单机可接受）。
 */
@Service
public class MonitorStatsService {

    public record MonitorEvent(String type, String message, Instant time) {}

    private static final int MAX_EVENTS = 200;

    private final AtomicInteger chatFailures = new AtomicInteger();
    private final AtomicInteger loginFailures = new AtomicInteger();
    private final ConcurrentLinkedQueue<MonitorEvent> events = new ConcurrentLinkedQueue<>();
    private final Counter chatFailureCounter;
    private final Counter loginFailureCounter;

    public MonitorStatsService(MeterRegistry registry) {
        chatFailureCounter = Counter.builder("chat.failures").register(registry);
        loginFailureCounter = Counter.builder("auth.login.failures").register(registry);
    }

    public void recordChatFailure(String message) {
        chatFailures.incrementAndGet();
        chatFailureCounter.increment();
        record("chat", message);
    }

    public void recordLoginFailure(String message) {
        loginFailures.incrementAndGet();
        loginFailureCounter.increment();
        record("login", message);
    }

    private void record(String type, String message) {
        events.add(new MonitorEvent(type, message != null ? message : "", Instant.now()));
        while (events.size() > MAX_EVENTS) events.poll();
    }

    public int chatFailures() { return chatFailures.get(); }
    public int loginFailures() { return loginFailures.get(); }
    public List<MonitorEvent> recentEvents() { return List.copyOf(events); }

    /** 返回并清零聊天失败计数（报警用增量）。 */
    public int drainChatFailures() { return chatFailures.getAndSet(0); }
}
