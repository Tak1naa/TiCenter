package com.exambank.chat.aop;

import com.exambank.common.entity.AuditLog;
import com.exambank.common.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    // In-memory admin session tracking (same pattern as AdminController)
    public static final Map<String, AdminSessionInfo> adminSessions = new ConcurrentHashMap<>();

    public record AdminSessionInfo(String username, String role, String ip, java.time.Instant createdAt) {}

    private final AuditLogRepository auditLogRepository;
    private final HttpServletRequest request;

    public AuditAspect(AuditLogRepository auditLogRepository, HttpServletRequest request) {
        this.auditLogRepository = auditLogRepository;
        this.request = request;
    }

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        Object result = joinPoint.proceed();

        try {
            String token = request.getHeader("X-Admin-Token");
            AdminSessionInfo session = token != null ? adminSessions.get(token) : null;
            String actor = session != null ? session.username() : "unknown";
            String ip = getClientIp();

            AuditLog entry = new AuditLog();
            entry.setActorName(actor);
            entry.setAction(auditable.action());
            entry.setTarget(joinPoint.getSignature().toShortString());
            entry.setIp(ip);
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Audit log write failed: {}", e.getMessage());
        }

        return result;
    }

    private String getClientIp() {
        if (request == null) return "unknown";
        return com.exambank.common.util.IpUtils.getClientIp(request);
    }
}
