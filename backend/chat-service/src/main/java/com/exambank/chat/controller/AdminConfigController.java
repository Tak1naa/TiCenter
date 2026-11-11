package com.exambank.chat.controller;

import com.exambank.common.entity.SystemConfig;
import com.exambank.common.repository.SystemConfigRepository;
import com.exambank.common.security.SecretValueCrypto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {

    private static final Set<String> SECRET_KEYS = Set.of("smtp_pass", "student_smtp_pass");

    private final SystemConfigRepository systemConfigRepository;
    private final SecretValueCrypto secretValueCrypto;

    public AdminConfigController(SystemConfigRepository systemConfigRepository,
                                 SecretValueCrypto secretValueCrypto) {
        this.systemConfigRepository = systemConfigRepository;
        this.secretValueCrypto = secretValueCrypto;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("X-Admin-Token") String token) {
        if (AdminAuthController.checkSession(token) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }

        // 敏感 key 脱敏：密码类不回传明文（前端只显示掩码，留空则不改）
        List<Map<String, String>> configs = systemConfigRepository.findAll().stream()
                .map(c -> {
                    String val = c.getConfigValue() != null ? c.getConfigValue() : "";
                    if (SECRET_KEYS.contains(c.getConfigKey()) && !val.isEmpty()) {
                        val = "******";
                    }
                    return Map.of("key", c.getConfigKey(), "value", val,
                            "description", c.getDescription() != null ? c.getDescription() : "");
                })
                .toList();

        return ResponseEntity.ok(Map.of("configs", configs));
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> update(@RequestHeader("X-Admin-Token") String token,
                                     @PathVariable String key,
                                     @RequestBody Map<String, String> body) {
        if (AdminAuthController.checkAdminRole(token) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }

        SystemConfig config = systemConfigRepository.findByConfigKey(key)
                .orElseGet(() -> {
                    SystemConfig sc = new SystemConfig();
                    sc.setConfigKey(key);
                    return sc;
                });

        String requestedValue = body.getOrDefault("value", "");
        // 掩码占位符不改（前端回传 ****** 时保留原值）
        if ("******".equals(requestedValue)) {
            requestedValue = systemConfigRepository.findByConfigKey(key)
                    .map(SystemConfig::getConfigValue).orElse("");
        } else if (SECRET_KEYS.contains(key) && !requestedValue.isBlank()) {
            if (!secretValueCrypto.isConfigured()) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "CONFIG_ENCRYPTION_KEY 未配置，拒绝保存明文秘密"));
            }
            requestedValue = secretValueCrypto.encrypt(requestedValue);
        }
        config.setConfigValue(requestedValue);
        if (body.containsKey("description")) config.setDescription(body.get("description"));
        systemConfigRepository.save(config);

        return ResponseEntity.ok(Map.of("message", "配置已更新"));
    }
}
