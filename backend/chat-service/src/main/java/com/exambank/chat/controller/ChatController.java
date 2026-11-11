package com.exambank.chat.controller;

import com.exambank.common.config.SessionStore;
import com.exambank.common.dto.ChatRequest;
import com.exambank.common.dto.Reference;
import com.exambank.common.entity.ChatHistory;
import com.exambank.common.repository.ChatHistoryRepository;
import com.exambank.chat.service.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final RagService ragService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ObjectMapper objectMapper;
    private final com.exambank.chat.service.MonitorStatsService monitorStatsService;
    private final com.exambank.chat.service.ChatAdmissionService admissionService;
    private final com.exambank.chat.service.ChatTelemetryService telemetryService;
    private final com.exambank.chat.service.DistributedRateLimiter rateLimiter;
    private final com.exambank.common.security.FileAccessPolicy fileAccessPolicy;
    private final com.exambank.common.security.DocumentContextService documentContexts;

    public ChatController(RagService ragService,
                          ChatHistoryRepository chatHistoryRepository,
                          ObjectMapper objectMapper,
                          com.exambank.chat.service.MonitorStatsService monitorStatsService,
                          com.exambank.chat.service.ChatAdmissionService admissionService,
                          com.exambank.chat.service.ChatTelemetryService telemetryService,
                          com.exambank.chat.service.DistributedRateLimiter rateLimiter,
                          com.exambank.common.security.FileAccessPolicy fileAccessPolicy,
                          com.exambank.common.security.DocumentContextService documentContexts) {
        this.ragService = ragService;
        this.chatHistoryRepository = chatHistoryRepository;
        this.objectMapper = objectMapper;
        this.monitorStatsService = monitorStatsService;
        this.admissionService = admissionService;
        this.telemetryService = telemetryService;
        this.rateLimiter = rateLimiter;
        this.fileAccessPolicy = fileAccessPolicy;
        this.documentContexts = documentContexts;
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> ask(@Valid @RequestBody ChatRequest request, jakarta.servlet.http.HttpServletRequest httpReq) {
        // sessionId 归属校验：必须以当前登录学生的学号开头（防 IDOR 读他人会话）
        SessionStore.Session session = SessionStore.get(getToken(httpReq));
        if (session == null || !"STUDENT".equals(session.role())) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        final String studentNo = session.principal();
        String rawSessionId = request.sessionId();
        final String sessionId = (rawSessionId == null || rawSessionId.isBlank())
                ? studentNo + "_" + UUID.randomUUID().toString().substring(0, 8)
                : rawSessionId;
        if (!sessionId.startsWith(studentNo + "_")) {
            return ResponseEntity.status(403).body(Map.of("error", "会话不属于当前账号"));
        }
        final String question = request.question();

        try {
            // Validate synchronously so a bad non-empty selection is an HTTP 400, not a late SSE error.
            var files = fileAccessPolicy.resolvePublicSelection(request.selectedFileIds(), request.selectedFiles());
            documentContexts.resolve(request.contextRefs(), files.stream().map(com.exambank.common.entity.ExamFile::getId).toList(), request.courseId());
        } catch (com.exambank.common.security.InvalidFileSelectionException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(), "code", com.exambank.common.security.InvalidFileSelectionException.CODE));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", Objects.toString(e.getReason(), "来源不可用")));
        }

        String clientIp = com.exambank.common.util.IpUtils.getClientIp(httpReq);
        String provider = request.provider() == null ? "default" : request.provider();
        if (!rateLimiter.allow("chat-student", studentNo, 30, 30)
                || !rateLimiter.allow("chat-ip", clientIp, 60, 60)
                || !rateLimiter.allow("chat-provider", provider, 600, 600)) {
            return ResponseEntity.status(429).header("Retry-After", "60")
                    .body(Map.of("error", "请求频率超过配额，请稍后重试"));
        }

        var lease = admissionService.reserve(provider);
        if (lease == null) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "30")
                    .body(Map.of("error", "当前请求过多，请稍后重试"));
        }

        final String requestId = UUID.randomUUID().toString();
        final long startedAt = System.nanoTime();
        telemetryService.start(requestId, studentNo, sessionId, request.provider(), request.model());

        SseEmitter emitter = new SseEmitter(300_000L);

        java.util.List<String> selectedFiles = request.selectedFiles();
        java.util.List<Long> selectedFileIds = request.selectedFileIds();

        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try (lease) {
                if (!lease.await()) {
                    telemetryService.fail(requestId, elapsedMillis(startedAt));
                    sendErrorEvent(emitter, "排队超时，请稍后重试");
                    return;
                }
                final long queueWaitMs = lease.waitMillis();
                ragService.streamAnswerSync(question, sessionId, selectedFileIds, selectedFiles, request.courseId(), request.contextRefs(),
                        request.provider(), request.model(), request.apiKey(), request.baseUrl(),
                        request.maxTokens() != null ? Math.min(request.maxTokens(), 8192) : 4096,
                        Boolean.TRUE.equals(request.multimodal()),
                        meta -> {
                            try {
                                telemetryService.retrieval(requestId,
                                        String.valueOf(meta.getOrDefault("retrievalMode", "unknown")),
                                        String.valueOf(meta.getOrDefault("documentVersion", "unknown")),
                                        ((Number) meta.getOrDefault("retrievedCount", 0)).intValue(), queueWaitMs,
                                        Boolean.TRUE.equals(meta.get("retrievalCacheHit")));
                                Map<String, Object> event = new LinkedHashMap<>(meta);
                                event.put("requestId", requestId);
                                event.put("queueWaitMs", queueWaitMs);
                                emitter.send(SseEmitter.event().name("meta")
                                        .data(objectMapper.writeValueAsString(event)));
                            } catch (Exception e) {
                                log.warn("SSE meta send failed: {}", e.getMessage());
                            }
                        },
                        delta -> {
                            try {
                                // JSON 包裹：换行转义为 \n，保证 SSE 单行 data，避免流式解析错位丢字符
                                emitter.send(SseEmitter.event().name("answer")
                                        .data(objectMapper.writeValueAsString(Map.of("content", delta))));
                            } catch (Exception e) {
                                log.error("SSE send error", e);
                            }
                        },
                        reason -> {
                            try {
                                emitter.send(SseEmitter.event().name("reason")
                                        .data(objectMapper.writeValueAsString(Map.of("content", reason))));
                            } catch (Exception e) {
                                log.error("SSE reason send error", e);
                            }
                        },
                        result -> {
                            try {
                                // 只发 AI 实际引用的页（已过滤），并去重
                                List<Reference> finalRefs = new ArrayList<>();
                                Set<String> seen2 = new HashSet<>();
                                for (Reference ref : result.references()) {
                                    String key = ref.getFileName() + ":" + ref.getPage();
                                    if (seen2.add(key)) finalRefs.add(ref);
                                }
                                for (Reference ref : finalRefs) {
                                    emitter.send(SseEmitter.event()
                                            .name("source")
                                            .data(objectMapper.writeValueAsString(ref)));
                                }
                                Map<String, Object> done = new LinkedHashMap<>();
                                done.put("references", finalRefs);
                                done.put("sessionId", sessionId);
                                done.put("requestId", requestId);
                                done.put("truncated", result.truncated());
                                done.put("inputTokensEstimate", result.inputTokensEstimate());
                                done.put("outputTokensEstimate", result.outputTokensEstimate());
                                done.put("inputTokens", result.inputTokensActual());
                                done.put("outputTokens", result.outputTokensActual());
                                done.put("providerCachedTokens", result.providerCachedTokens());
                                done.put("usageSource", result.usageSource());
                                done.put("prefixCacheCandidate", result.prefixCacheCandidate());
                                emitter.send(SseEmitter.event().name("done")
                                        .data(objectMapper.writeValueAsString(done)));
                                telemetryService.complete(requestId, elapsedMillis(startedAt), finalRefs,
                                        result.inputTokensEstimate(), result.outputTokensEstimate(),
                                        result.inputTokensActual(), result.outputTokensActual(),
                                        result.providerCachedTokens(), result.usageSource(),
                                        result.prefixCacheCandidate());
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.error("Stream error", error);
                            telemetryService.fail(requestId, elapsedMillis(startedAt));
                            sendErrorEvent(emitter, translateError(error));
                        });

            } catch (Exception e) {
                log.error("Chat ask error", e);
                telemetryService.fail(requestId, elapsedMillis(startedAt));
                sendErrorEvent(emitter, translateError(e));
            } finally {
                finished.set(true);
            }
        });
        Runnable cancelUpstream = () -> {
            if (!finished.get()) worker.interrupt();
        };
        emitter.onTimeout(cancelUpstream);
        emitter.onError(error -> cancelUpstream.run());
        emitter.onCompletion(cancelUpstream);
        worker.start();

        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    public record FeedbackRequest(boolean helpful, String errorType, @jakarta.validation.constraints.Size(max = 1000) String comment) {}

    @PostMapping("/{requestId}/feedback")
    public ResponseEntity<?> feedback(@PathVariable String requestId,
                                      @Valid @RequestBody FeedbackRequest feedback,
                                      HttpServletRequest request) {
        SessionStore.Session session = SessionStore.get(getToken(request));
        if (session == null || !"STUDENT".equals(session.role())) return ResponseEntity.status(401).build();
        if (feedback.errorType() != null && !Set.of("ANSWER", "CITATION", "RETRIEVAL", "OTHER").contains(feedback.errorType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知的错误类型"));
        }
        try {
            telemetryService.feedback(requestId, session.principal(), feedback.helpful(), feedback.errorType(), feedback.comment());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /** 把异常翻译为中文提示，通过 SSE error 事件返回（前端可见，不静默）。 */
    private void sendErrorEvent(SseEmitter emitter, String message) {
        // 监控埋点：聊天失败计数
        if (message != null && !message.contains("会话不属于") && !message.contains("未登录")) {
            monitorStatsService.recordChatFailure(message);
        }
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(objectMapper.writeValueAsString(Map.of("error", message))));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private String translateError(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("Connection refused") || msg.contains("connect timed out")
                || msg.contains("HttpRetryException")) {
            return "AI 服务暂时不可用，请稍后重试。";
        }
        // Detailed upstream messages stay server-side; they may contain provider request metadata.
        return "请求失败，请稍后重试。";
    }

    @GetMapping("/history")
    public ResponseEntity<List<ChatHistory>> history(@RequestParam("sessionId") String sessionId,
                                                     jakarta.servlet.http.HttpServletRequest httpReq) {
        SessionStore.Session session = SessionStore.get(getToken(httpReq));
        if (session == null || !"STUDENT".equals(session.role())) {
            return ResponseEntity.status(401).build();
        }
        if (!sessionId.startsWith(session.principal() + "_")) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(ragService.getHistory(sessionId));
    }

    private String getToken(HttpServletRequest req) {
        String t = req.getHeader("X-Auth-Token");
        if (t == null || t.isEmpty()) t = req.getHeader("X-Student-Token");
        if (t == null || t.isEmpty()) t = req.getHeader("X-Admin-Token");
        return t;
    }

    private SseEmitter errorEmitter(int status, String message) {
        SseEmitter e = new SseEmitter();
        try {
            e.send(SseEmitter.event().name("error")
                    .data(objectMapper.writeValueAsString(Map.of("error", message))));
        } catch (Exception ex) {
            log.error("SSE error send failed", ex);
        }
        e.complete();
        return e;
    }
}
