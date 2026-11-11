package com.exambank.chat.controller;

import com.exambank.common.entity.AuditLog;
import com.exambank.common.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/logs")
public class AdminAuditLogController {

    private final AuditLogRepository auditLogRepository;

    public AdminAuditLogController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("X-Admin-Token") String token,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "30") int size,
                                   @RequestParam(defaultValue = "") String action) {
        if (AdminAuthController.checkSession(token) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }

        var items = auditLogRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> result = items.getContent().stream()
                .filter(l -> action.isEmpty() || l.getAction().contains(action))
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", l.getId());
                    m.put("actorName", l.getActorName() != null ? l.getActorName() : "system");
                    m.put("action", l.getAction());
                    m.put("target", l.getTarget() != null ? l.getTarget() : "");
                    m.put("detail", l.getDetail() != null ? l.getDetail() : "");
                    m.put("ip", l.getIp() != null ? l.getIp() : "");
                    m.put("createdAt", l.getCreatedAt().toString());
                    return m;
                }).toList();

        return ResponseEntity.ok(Map.of(
                "items", result,
                "total", items.getTotalElements(),
                "page", page, "size", size));
    }
}
