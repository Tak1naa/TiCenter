package com.exambank.chat.service;

import com.exambank.common.entity.ChatFeedback;
import com.exambank.common.entity.ChatRequestTrace;
import com.exambank.common.repository.ChatFeedbackRepository;
import com.exambank.common.repository.ChatRequestTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChatTelemetryService {
    private final ChatRequestTraceRepository traceRepository;
    private final ChatFeedbackRepository feedbackRepository;
    private final ObjectMapper objectMapper;

    public ChatTelemetryService(ChatRequestTraceRepository traceRepository,
                                ChatFeedbackRepository feedbackRepository,
                                ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.feedbackRepository = feedbackRepository;
        this.objectMapper = objectMapper;
    }

    public void start(String requestId, String studentNo, String sessionId, String provider, String model) {
        ChatRequestTrace trace = new ChatRequestTrace();
        trace.setRequestId(requestId);
        trace.setStudentNo(studentNo);
        trace.setSessionId(sessionId);
        trace.setProvider(provider);
        trace.setModel(model);
        traceRepository.save(trace);
    }

    @Transactional
    public void retrieval(String requestId, String mode, String documentVersion, int retrievedCount,
                          long queueWaitMs, boolean retrievalCacheHit) {
        traceRepository.findById(requestId).ifPresent(trace -> {
            trace.setRetrievalMode(mode);
            trace.setDocumentVersion(documentVersion);
            trace.setRetrievedCount(retrievedCount);
            trace.setQueueWaitMs(queueWaitMs);
            trace.setRetrievalCacheHit(retrievalCacheHit);
        });
    }

    @Transactional
    public void complete(String requestId, long latencyMs, List<?> references,
                         long inputTokensEstimate, long outputTokensEstimate,
                         long inputTokensActual, long outputTokensActual, long providerCachedTokens,
                         String usageSource, boolean prefixCacheCandidate) {
        traceRepository.findById(requestId).ifPresent(trace -> {
            trace.setStatus("COMPLETED");
            trace.setLatencyMs(latencyMs);
            trace.setCompletedAt(LocalDateTime.now());
            trace.setInputTokensEstimate(inputTokensEstimate);
            trace.setOutputTokensEstimate(outputTokensEstimate);
            trace.setInputTokensActual(inputTokensActual > 0 ? inputTokensActual : null);
            trace.setOutputTokensActual(outputTokensActual > 0 ? outputTokensActual : null);
            trace.setProviderCachedTokens(providerCachedTokens);
            trace.setUsageSource(usageSource);
            trace.setPrefixCacheCandidate(prefixCacheCandidate);
            try { trace.setReferencesJson(objectMapper.writeValueAsString(references)); }
            catch (Exception ignored) { trace.setReferencesJson("[]"); }
        });
    }

    @Transactional
    public void fail(String requestId, long latencyMs) {
        traceRepository.findById(requestId).ifPresent(trace -> {
            trace.setStatus("FAILED");
            trace.setLatencyMs(latencyMs);
            trace.setCompletedAt(LocalDateTime.now());
        });
    }

    @Transactional
    public void feedback(String requestId, String studentNo, boolean helpful, String errorType, String comment) {
        ChatRequestTrace trace = traceRepository.findById(requestId)
                .filter(t -> t.getStudentNo().equals(studentNo))
                .orElseThrow(() -> new IllegalArgumentException("请求不存在或不属于当前账号"));
        if (feedbackRepository.existsByRequestIdAndStudentNo(trace.getRequestId(), studentNo)) {
            throw new IllegalArgumentException("该回答已经提交过反馈");
        }
        ChatFeedback feedback = new ChatFeedback();
        feedback.setRequestId(requestId);
        feedback.setStudentNo(studentNo);
        feedback.setHelpful(helpful);
        feedback.setErrorType(errorType);
        feedback.setComment(comment == null ? null : comment.strip());
        feedbackRepository.save(feedback);
    }
}
