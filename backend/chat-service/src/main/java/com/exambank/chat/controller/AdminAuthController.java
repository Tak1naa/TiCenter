package com.exambank.chat.controller;

import com.exambank.common.config.IpTrackingFilter;
import com.exambank.common.config.SessionStore;
import com.exambank.common.dto.LoginRequest;
import com.exambank.common.entity.AdminUser;
import com.exambank.common.repository.AdminUserRepository;
import com.exambank.common.repository.ChatHistoryRepository;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.common.repository.TextChunkRepository;
import com.exambank.chat.aop.AuditAspect;
import com.exambank.chat.aop.Auditable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.exambank.common.util.SessionCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private static final SecureRandom secureRandom = new SecureRandom();
    static final long SESSION_TTL_MINUTES = 30;

    private record RateWindow(long startMs, int count) {}
    private static final Map<String, RateWindow> loginRateMap = new ConcurrentHashMap<>();

    private final AdminUserRepository adminUserRepository;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ExamFileRepository examFileRepository;
    private final TextChunkRepository textChunkRepository;

    /** 删除文件时通知检索模块清理 Qdrant 向量（防语义检索命中已删文件）。 */
    private final org.springframework.web.client.RestTemplate vectorRestTemplate = createVectorRestTemplate();

    private static org.springframework.web.client.RestTemplate createVectorRestTemplate() {
        org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
        String token = System.getenv("API_SERVICE_TOKEN");
        if (token != null && !token.isEmpty()) {
            rt.getInterceptors().add((req, body, execution) -> {
                req.getHeaders().set("X-Api-Token", token);
                return execution.execute(req, body);
            });
        }
        return rt;
    }

    /** 仪表盘目录大小统计缓存（60s），避免每次打开仪表盘全量遍历题库目录。 */
    private static volatile long cachedDirSize = -1;
    private static volatile long cachedDirSizeAt = 0;

    @Value("${app.exam-bank.root-path:/home/takina/AiQuestionBank/data/exam-bank}")
    private String examBankRoot;

    @Value("${app.vector.service-url:http://localhost:39182}")
    private String vectorServiceUrl;

    public AdminAuthController(AdminUserRepository adminUserRepository,
                               ChatHistoryRepository chatHistoryRepository,
                               ExamFileRepository examFileRepository,
                               TextChunkRepository textChunkRepository) {
        this.adminUserRepository = adminUserRepository;
        this.chatHistoryRepository = chatHistoryRepository;
        this.examFileRepository = examFileRepository;
        this.textChunkRepository = textChunkRepository;
    }

    // ── Auth: login/logout/me ─────────────────────────────────────

    private void ensureAdminExists() {
        if (adminUserRepository.findByUsername("admin").isEmpty()) {
            String seedPassword = System.getenv("ADMIN_SEED_PASSWORD");
            if (seedPassword == null || seedPassword.isBlank()) {
                throw new IllegalStateException("ADMIN_SEED_PASSWORD 环境变量未设置，无法初始化管理员账号");
            }
            AdminUser admin = new AdminUser();
            admin.setUsername("admin");
            admin.setPasswordHash(encoder.encode(seedPassword));
            admin.setDisplayName("超级管理员");
            admin.setRole("SUPER_ADMIN");
            adminUserRepository.save(admin);
            log.info("Seeded SUPER_ADMIN user: admin");
        }
    }

    @PostMapping("/api/admin/auth/login")
    @Transactional
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        ensureAdminExists();
        String ip = getClientIp(request);
        if (!checkRateLimit(ip))
            return ResponseEntity.status(429).body(Map.of("error", "登录尝试过于频繁，请稍后再试"));

        AdminUser admin = adminUserRepository.findByUsername(req.username()).orElse(null);
        if (admin == null || !encoder.matches(req.password(), admin.getPasswordHash())) {
            log.warn("Admin login failed: {} from IP {}", req.username(), ip);
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        if ("DISABLED".equals(admin.getStatus()))
            return ResponseEntity.status(403).body(Map.of("error", "账号已被禁用"));

        String token = SessionStore.create(admin.getRole(), admin.getUsername(), ip);
        SessionCookie.add(response, request, SessionCookie.ADMIN, token);
        // 兼容 AuditAspect 的会话读取（审计日志用）
        AuditAspect.adminSessions.put(token,
                new AuditAspect.AdminSessionInfo(admin.getUsername(), admin.getRole(), ip, Instant.now()));
        admin.setLastLoginIp(ip);
        admin.setLastLoginAt(java.time.LocalDateTime.now());
        adminUserRepository.save(admin);

        log.info("Admin login: {} ({}) from IP {}", admin.getUsername(), admin.getRole(), ip);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("username", admin.getUsername());
        r.put("displayName", admin.getDisplayName() != null ? admin.getDisplayName() : admin.getUsername());
        r.put("role", admin.getRole());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/api/admin/auth/logout")
    @Auditable(action = "ADMIN_LOGOUT")
    public ResponseEntity<Map<String, Object>> logout(@RequestHeader("X-Admin-Token") String token,
                                                       HttpServletResponse response) {
        // 双清：SessionStore（统一会话）+ AuditAspect（审计读取）——否则 token 30 分钟内仍可复用
        SessionStore.remove(token);
        AuditAspect.adminSessions.remove(token);
        SessionCookie.clear(response, SessionCookie.ADMIN);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/api/admin/auth/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader("X-Admin-Token") String token) {
        var session = checkSession(token);
        if (session == null) return unauthorized();
        AdminUser admin = adminUserRepository.findByUsername(session.username()).orElse(null);
        if (admin == null) return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("username", admin.getUsername());
        r.put("displayName", admin.getDisplayName() != null ? admin.getDisplayName() : admin.getUsername());
        r.put("role", admin.getRole());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/api/admin/auth/change-password")
    @Auditable(action = "ADMIN_CHANGE_PASSWORD")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestHeader("X-Admin-Token") String token,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        var session = checkSession(token);
        if (session == null) return unauthorized();
        // 限流：防无限猜旧密码（按 IP 5 次/分）
        if (!checkRateLimit(getClientIp(request))) {
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请稍后再试"));
        }
        AdminUser admin = adminUserRepository.findByUsername(session.username()).orElse(null);
        if (admin == null) return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));

        String oldPwd = body.getOrDefault("oldPassword", "");
        String newPwd = body.getOrDefault("newPassword", "");
        // 与登录密码策略一致：至少 8 位且含字母和数字
        if (!newPwd.matches("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码至少 8 位，且必须包含字母和数字"));
        }
        if (!encoder.matches(oldPwd, admin.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "旧密码不正确"));
        }
        // bcrypt 加盐哈希（cost=12），改密后使该用户全部会话失效（安全轮换）
        admin.setPasswordHash(encoder.encode(newPwd));
        adminUserRepository.save(admin);
        SessionStore.invalidateByPrincipal(admin.getRole(), admin.getUsername());
        log.info("Admin {} changed password from IP {}", admin.getUsername(), getClientIp(request));
        return ResponseEntity.ok(Map.of("message", "密码已修改，请重新登录"));
    }

    // ── Dashboard ─────────────────────────────────────────────────

    @GetMapping("/api/admin/stats")
    public ResponseEntity<Map<String, Object>> stats(@RequestHeader(value = "X-Admin-Token", defaultValue = "") String token) {
        if (checkSession(token) == null) return unauthorized();

        Map<String, Object> data = new LinkedHashMap<>();
        long totalFiles = examFileRepository.count();
        long completed = examFileRepository.findByParseStatus("completed").size();
        long failed = examFileRepository.findByParseStatus("failed").size();
        long processing = examFileRepository.findByParseStatus("processing").size();
        data.put("files", Map.of("total", totalFiles, "completed", completed,
                "failed", failed, "processing", processing));

        long totalChats = chatHistoryRepository.count();
        long userMessages = chatHistoryRepository.countByRole("user");
        data.put("chats", Map.of("total", totalChats, "userMessages", userMessages,
                "aiMessages", totalChats - userMessages));

        File root = new File(examBankRoot);
        long totalSpace = root.getTotalSpace();
        long freeSpace = root.getFreeSpace();
        long usedSpace = totalSpace - freeSpace;
        data.put("storage", Map.of(
                "totalGB", String.format("%.1f", totalSpace / 1024.0 / 1024.0 / 1024.0),
                "usedGB", String.format("%.1f", usedSpace / 1024.0 / 1024.0 / 1024.0),
                "freeGB", String.format("%.1f", freeSpace / 1024.0 / 1024.0 / 1024.0),
                "usedPercent", totalSpace > 0 ? (int) (usedSpace * 100 / totalSpace) : 0));
        data.put("examBankSizeMB", String.format("%.1f", cachedDirSize(root) / 1024.0 / 1024.0));

        List<Map<String, Object>> ipStats = IpTrackingFilter.ipAccessCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
                .limit(20).map(e -> {
                    String ip = e.getKey();
                    return Map.<String, Object>of("ip", ip, "count", e.getValue().get(),
                            "firstAccess", IpTrackingFilter.ipFirstAccess.getOrDefault(ip, "-"),
                            "lastAccess", IpTrackingFilter.ipLastAccess.getOrDefault(ip, "-"));
                }).collect(Collectors.toList());
        data.put("ipStats", ipStats);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/api/admin/chat-logs")
    public ResponseEntity<?> chatLogs(@RequestHeader("X-Admin-Token") String token,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        if (checkSession(token) == null) return unauthorized();
        size = Math.min(Math.max(size, 1), 100); // 拉取上限，防全量拖库
        var items = chatHistoryRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(Map.of("items", items.getContent(),
                "total", items.getTotalElements(), "page", page, "size", size));
    }

    @GetMapping("/api/admin/upload-logs")
    public ResponseEntity<?> uploadLogs(@RequestHeader("X-Admin-Token") String token) {
        if (checkSession(token) == null) return unauthorized();
        var items = examFileRepository.findAll(
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(items.getContent());
    }

    @GetMapping("/api/admin/files")
    public ResponseEntity<?> filesList(@RequestHeader(value = "X-Admin-Token", defaultValue = "") String token,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "30") int size,
                                        @RequestParam(defaultValue = "") String search,
                                        @RequestParam(defaultValue = "") String status) {
        if (checkSession(token) == null) return unauthorized();
        size = Math.min(Math.max(size, 1), 100);
        // 搜索/状态过滤下推到 SQL（先过滤后分页，翻页与 total 才正确）
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        org.springframework.data.domain.Page<com.exambank.common.entity.ExamFile> all;
        boolean hasSearch = search != null && !search.isBlank();
        boolean hasStatus = status != null && !status.isBlank();
        if (hasSearch && hasStatus) {
            all = examFileRepository.findByFileNameContainingAndParseStatus(search, status, pageable);
        } else if (hasSearch) {
            all = examFileRepository.findByFileNameContaining(search, pageable);
        } else if (hasStatus) {
            all = examFileRepository.findByParseStatus(status, pageable);
        } else {
            all = examFileRepository.findAll(pageable);
        }
        var filtered = all.getContent().stream()
                .map(f -> Map.<String, Object>of(
                        "id", f.getId(), "fileName", f.getFileName() != null ? f.getFileName() : "",
                        "filePath", f.getFilePath() != null ? f.getFilePath() : "",
                        "fileType", f.getFileType() != null ? f.getFileType() : "",
                        "fileSize", f.getFileSize() != null ? f.getFileSize() : 0L,
                        "parseStatus", f.getParseStatus() != null ? f.getParseStatus() : "",
                        "parseMethod", f.getParseMethod() != null ? f.getParseMethod() : "",
                        "totalPages", f.getTotalPages() != null ? f.getTotalPages() : 0,
                        "createdAt", f.getCreatedAt() != null ? f.getCreatedAt().toString() : ""))
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(Map.of("items", filtered, "total", all.getTotalElements()));
    }

    @PostMapping("/api/admin/files/upload")
    @Auditable(action = "ADMIN_UPLOAD_FILE")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestHeader(value = "X-Admin-Token", defaultValue = "") String token,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        if (checkWriteRole(token) == null) return unauthorized();
        try {
            // 类型白名单（防上传 .html/.sh/.jsp 等可执行/脚本文件）+ 大小上限
            String origName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
            String ext = origName.contains(".") ? origName.substring(origName.lastIndexOf('.')).toLowerCase() : "";
            Set<String> ALLOWED_EXTS = Set.of(".pdf", ".docx", ".doc", ".pptx", ".ppt", ".xlsx", ".xls",
                    ".txt", ".md", ".png", ".jpg", ".jpeg", ".bmp", ".tiff");
            if (!ALLOWED_EXTS.contains(ext)) {
                return ResponseEntity.badRequest().body(Map.of("error", "不支持的文件类型: " + ext));
            }
            if (file.getSize() > 100L * 1024 * 1024) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件超过 100MB 上限"));
            }
            java.nio.file.Path rootPath = java.nio.file.Path.of(examBankRoot);
            java.nio.file.Files.createDirectories(rootPath);
            // 文件名净化：只取 basename + 白名单字符（防 ../ 路径穿越）
            String rawName = origName.isEmpty() ? "upload" : origName;
            String fileName = java.nio.file.Path.of(rawName).getFileName().toString()
                    .replaceAll("[^\\w\\u4e00-\\u9fa5.\\-]", "_");
            if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件名非法"));
            }
            java.nio.file.Path dest = rootPath.resolve(fileName);
            file.transferTo(dest.toFile());
            examFileRepository.findByFilePath(dest.toString()).ifPresentOrElse(
                    ef -> { ef.setParseStatus("pending"); examFileRepository.save(ef); },
                    () -> {
                        var ef = new com.exambank.common.entity.ExamFile();
                        ef.setFilePath(dest.toString()); ef.setFileName(fileName); ef.setFileSize(file.getSize());
                        ef.setFileType(fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "");
                        ef.setParseStatus("pending"); examFileRepository.save(ef);
                    });
            log.info("Admin uploaded: {}", fileName);
            return ResponseEntity.ok(Map.of("message", "文件已上传", "fileName", fileName));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "上传失败，请稍后重试"));
        }
    }

    @PostMapping("/api/admin/files/delete")
    @Auditable(action = "ADMIN_DELETE_FILE")
    public ResponseEntity<Map<String, Object>> deleteFile(@RequestHeader(value = "X-Admin-Token", defaultValue = "") String token,
                                                           @RequestBody Map<String, Object> body) {
        if (checkWriteRole(token) == null) return unauthorized();
        Object idObj = body.get("id");
        Long id = idObj instanceof Number ? ((Number) idObj).longValue() : null;
        if (id == null) return ResponseEntity.badRequest().body(Map.of("error", "缺少文件 ID"));
        var ef = examFileRepository.findById(id).orElse(null);
        if (ef == null) return ResponseEntity.status(404).body(Map.of("error", "文件不存在"));
        try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(ef.getFilePath())); } catch (Exception ignored) {}
        // 级联清理：删 text_chunks + 通知检索模块删除 Qdrant points
        List<Long> chunkIds = textChunkRepository.findByFileIdOrderByChunkIndex(ef.getId())
                .stream().map(c -> c.getId()).toList();
        textChunkRepository.deleteByFileId(ef.getId());
        if (!chunkIds.isEmpty()) notifyVectorDelete(chunkIds);
        examFileRepository.delete(ef);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /** 通知检索模块清理 Qdrant 索引（失败仅记日志，不影响删除主流程）。 */
    private void notifyVectorDelete(List<Long> chunkIds) {
        try {
            vectorRestTemplate.postForEntity(
                    vectorServiceUrl + "/api/vector/delete",
                    Map.of("chunkIds", chunkIds),
                    String.class);
        } catch (Exception e) {
            log.warn("Vector delete notification failed: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    static AuditAspect.AdminSessionInfo checkSession(String token) {
        if (token == null || token.isEmpty()) return null;
        // 统一会话存储校验（双写：AuditAspect.adminSessions 保持同步）
        SessionStore.Session s = SessionStore.get(token);
        if (s == null) {
            AuditAspect.adminSessions.remove(token);
            return null;
        }
        SessionStore.touch(token);
        AuditAspect.adminSessions.put(token,
                new AuditAspect.AdminSessionInfo(s.principal(), s.role(), s.ip(), Instant.now()));
        return new AuditAspect.AdminSessionInfo(s.principal(), s.role(), s.ip(), Instant.now());
    }

    /** RBAC：写操作要求非 VIEWER 角色（VIEWER 只读）。返回 null 表示无权限。 */
    static AuditAspect.AdminSessionInfo checkWriteRole(String token) {
        var session = checkSession(token);
        if (session == null) return null;
        if ("VIEWER".equals(session.role())) return null;
        return session;
    }

    /** Sensitive account/config/data operations are limited to ADMIN and SUPER_ADMIN. */
    static AuditAspect.AdminSessionInfo checkAdminRole(String token) {
        var session = checkSession(token);
        if (session == null || !("ADMIN".equals(session.role()) || "SUPER_ADMIN".equals(session.role()))) return null;
        return session;
    }

    private String generateToken() { byte[] b = new byte[32]; secureRandom.nextBytes(b); StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }

    static String getClientIp(HttpServletRequest request) {
        return com.exambank.common.util.IpUtils.getClientIp(request);
    }

    private boolean checkRateLimit(String ip) {
        // 周期性清理过期条目（防限流 Map 无限增长）
        if (loginRateMap.size() > 10_000) {
            loginRateMap.entrySet().removeIf(e ->
                    System.currentTimeMillis() - e.getValue().startMs() > 60_000);
        }
        var rw = loginRateMap.get(ip); long now = System.currentTimeMillis();
        if (rw != null && now - rw.startMs() < 60_000) {
            if (rw.count() >= 5) return false;
            loginRateMap.put(ip, new RateWindow(rw.startMs(), rw.count() + 1));
        } else { loginRateMap.put(ip, new RateWindow(now, 1)); }
        return true;
    }

    private long cachedDirSize(File dir) {
        long now = System.currentTimeMillis();
        long cached = cachedDirSize;
        if (cached >= 0 && now - cachedDirSizeAt < 60_000) return cached;
        long size = 0;
        try {
            var p = dir.toPath();
            if (java.nio.file.Files.exists(p)) {
                size = java.nio.file.Files.walk(p).filter(f -> f.toFile().isFile())
                        .mapToLong(f -> f.toFile().length()).sum();
            }
        } catch (Exception ignored) {}
        cachedDirSize = size;
        cachedDirSizeAt = now;
        return size;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
    }
}
