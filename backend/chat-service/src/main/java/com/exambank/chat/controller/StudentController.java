package com.exambank.chat.controller;

import com.exambank.common.config.SessionStore;
import com.exambank.common.dto.*;
import com.exambank.common.entity.Student;
import com.exambank.common.repository.ChatHistoryRepository;
import com.exambank.common.repository.StudentRepository;
import com.exambank.common.util.TokenGenerator;
import com.exambank.common.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.exambank.common.util.SessionCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/student")
public class StudentController {

    private static final Logger log = LoggerFactory.getLogger(StudentController.class);
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    private static final Pattern STUDENT_NO_PATTERN = Pattern.compile("^\\d{8}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    // Session 校验结果（统一存储 SessionStore，这里保留薄包装）
    private record SessionInfo(String studentNo, String ip, Instant createdAt) {}

    // Login rate limiting: {ip -> {windowStartMs, count}}
    private record RateWindow(long startMs, int count) {}
    private static final Map<String, RateWindow> loginRateMap = new ConcurrentHashMap<>();
    private static final int LOGIN_RATE_LIMIT = 5;
    private static final long LOGIN_RATE_WINDOW_MS = 60_000;

    private final StudentRepository studentRepository;
    private final ChatHistoryRepository chatHistoryRepository;
    private final com.exambank.chat.service.MonitorStatsService monitorStatsService;
    private final com.exambank.chat.service.MailAlarmService mailAlarmService;

    public StudentController(StudentRepository studentRepository,
                             ChatHistoryRepository chatHistoryRepository,
                             com.exambank.chat.service.MonitorStatsService monitorStatsService,
                             com.exambank.chat.service.MailAlarmService mailAlarmService) {
        this.studentRepository = studentRepository;
        this.chatHistoryRepository = chatHistoryRepository;
        this.monitorStatsService = monitorStatsService;
        this.mailAlarmService = mailAlarmService;
    }

    // ── Registration ──────────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest req,
                                                        HttpServletRequest httpReq) {
        // 注册限流：防批量刷号（与登录同策略，按 IP 5 次/分）
        if (!checkRateLimit(getClientIp(httpReq))) {
            return ResponseEntity.status(429).body(Map.of("error", "注册过于频繁，请稍后再试"));
        }
        if (req.studentNo() == null || !STUDENT_NO_PATTERN.matcher(req.studentNo()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "学号必须为8位数字"));
        }
        if (req.password() == null || !PASSWORD_PATTERN.matcher(req.password()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码至少8位，且必须包含字母和数字"));
        }
        if (req.email() == null || !EMAIL_PATTERN.matcher(req.email()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "邮箱格式不正确"));
        }
        if (studentRepository.findByStudentNo(req.studentNo()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "该学号已被注册"));
        }
        if (studentRepository.findByEmail(req.email()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "该邮箱已被注册"));
        }

        Student student = new Student();
        student.setStudentNo(req.studentNo());
        student.setPasswordHash(encoder.encode(req.password()));
        student.setEmail(req.email());
        student.setVerificationToken(TokenGenerator.digest(generateToken()));
        studentRepository.save(student);

        log.info("Student registered: {} email={}", req.studentNo(), req.email());
        return ResponseEntity.ok(Map.of("message", "注册成功", "studentNo", req.studentNo()));
    }

    // ── Login ─────────────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        String ip = getClientIp(request);

        if (!checkRateLimit(ip)) {
            return ResponseEntity.status(429).body(Map.of("error", "登录尝试过于频繁，请稍后再试"));
        }

        if (req.username() == null || req.password() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "学号或密码错误"));
        }

        Student student = studentRepository.findByStudentNo(req.username()).orElse(null);
        if (student == null || !encoder.matches(req.password(), student.getPasswordHash())) {
            log.warn("Failed student login: {} from IP {}", req.username(), ip);
            monitorStatsService.recordLoginFailure("学号 " + req.username() + " 登录失败");
            return ResponseEntity.status(401).body(Map.of("error", "学号或密码错误"));
        }

        if ("DISABLED".equals(student.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "账号已被禁用，请联系管理员"));
        }

        // Generate session token（统一会话存储）
        String token = SessionStore.create("STUDENT", student.getStudentNo(), ip);
        SessionCookie.add(response, request, SessionCookie.STUDENT, token);

        // Update login tracking
        student.setLastLoginAt(LocalDateTime.now());
        student.setLastLoginIp(ip);
        studentRepository.save(student);

        log.info("Student login: {} from IP {}", student.getStudentNo(), ip);
        return ResponseEntity.ok(Map.of(
                "studentNo", student.getStudentNo(),
                "email", student.getEmail() != null ? student.getEmail() : "",
                "emailVerified", student.isEmailVerified()
        ));
    }

    // ── Logout ────────────────────────────────────────────────────

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestHeader("X-Student-Token") String token,
                                                       HttpServletResponse response) {
        SessionStore.remove(token);
        SessionCookie.clear(response, SessionCookie.STUDENT);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ── Current user info ─────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader("X-Student-Token") String token) {
        SessionInfo session = checkSession(token);
        if (session == null) return unauthorized();

        Student student = studentRepository.findByStudentNo(session.studentNo()).orElse(null);
        if (student == null) return ResponseEntity.status(404).body(Map.of("error", "账号不存在"));

        return ResponseEntity.ok(Map.of(
                "studentNo", student.getStudentNo(),
                "email", student.getEmail() != null ? student.getEmail() : "",
                "emailVerified", student.isEmailVerified(),
                "createdAt", student.getCreatedAt().toString()
        ));
    }

    // ── Change password ───────────────────────────────────────────

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestHeader("X-Student-Token") String token,
            @RequestBody ChangePasswordRequest req) {
        SessionInfo session = checkSession(token);
        if (session == null) return unauthorized();

        Student student = studentRepository.findByStudentNo(session.studentNo()).orElse(null);
        if (student == null) return ResponseEntity.status(404).body(Map.of("error", "账号不存在"));

        if (!encoder.matches(req.oldPassword(), student.getPasswordHash())) {
            return ResponseEntity.badRequest().body(Map.of("error", "旧密码不正确"));
        }
        if (req.newPassword() == null || !PASSWORD_PATTERN.matcher(req.newPassword()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码至少8位，且必须包含字母和数字"));
        }

        student.setPasswordHash(encoder.encode(req.newPassword()));
        studentRepository.save(student);

        // Invalidate all sessions for this user (security rotation)
        SessionStore.invalidateByPrincipal("STUDENT", student.getStudentNo());
        SessionStore.create("STUDENT", student.getStudentNo(), session.ip()); // keep current session

        log.info("Password changed for student {}", student.getStudentNo());
        return ResponseEntity.ok(Map.of("message", "密码修改成功"));
    }

    // ── Forgot password ───────────────────────────────────────────

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody ForgotPasswordRequest req,
                                                              jakarta.servlet.http.HttpServletRequest httpReq) {
        // 限流：防邮箱轰炸（复用登录限流，按 IP 5 次/分）
        String ip = getClientIp(httpReq);
        if (!checkRateLimit(ip)) {
            return ResponseEntity.status(429).body(Map.of("error", "请求过于频繁，请稍后再试"));
        }
        Student student = studentRepository.findByEmail(req.email()).orElse(null);
        if (student == null) {
            // Don't reveal whether the email exists
            return ResponseEntity.ok(Map.of("message", "如果该邮箱已注册，重置链接已发送"));
        }

        String resetToken = generateToken();
        student.setResetToken(TokenGenerator.digest(resetToken));
        student.setResetTokenExpiry(Instant.now().plusSeconds(3600)); // 1 hour expiry
        studentRepository.save(student);

        // 发送重置邮件（独立学生 SMTP 配置，未配则降级日志）
        // 链接 base URL 可配置（app_base_url，生产设 https 域名），默认本地
        String baseUrl = mailAlarmService.getConfig("app_base_url", "http://localhost:5173");
        String resetLink = baseUrl + "/reset-password?token=" + resetToken;
        boolean sent = mailAlarmService.sendStudentEmail(req.email(), "【AI题库】密码重置",
                "您好，\n\n您请求重置账号 " + student.getStudentNo() + " 的密码。\n" +
                        "请在 1 小时内点击以下链接重置密码：\n" + resetLink + "\n\n" +
                        "如果这不是您本人的操作，请忽略此邮件。\n\n—— AI 智能题库");
        if (!sent) {
            log.warn("Password reset email could not be delivered for student {}; token was not logged",
                    student.getStudentNo());
        }

        return ResponseEntity.ok(Map.of("message", "如果该邮箱已注册，重置链接已发送"));
    }

    // ── Reset password (with token) ───────────────────────────────

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody ResetPasswordRequest req) {
        if (req.token() == null || req.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "重置链接无效或已过期"));
        }
        Student student = studentRepository.findByResetToken(TokenGenerator.digest(req.token())).orElse(null);
        if (student == null || student.getResetTokenExpiry() == null
                || Instant.now().isAfter(student.getResetTokenExpiry())) {
            return ResponseEntity.badRequest().body(Map.of("error", "重置链接无效或已过期"));
        }
        if (req.newPassword() == null || !PASSWORD_PATTERN.matcher(req.newPassword()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码至少8位，且必须包含字母和数字"));
        }

        student.setPasswordHash(encoder.encode(req.newPassword()));
        student.setResetToken(null);
        student.setResetTokenExpiry(null);
        studentRepository.save(student);

        // Invalidate all sessions
        SessionStore.invalidateByPrincipal("STUDENT", student.getStudentNo());

        log.info("Password reset successful for {}", student.getStudentNo());
        return ResponseEntity.ok(Map.of("message", "密码重置成功，请重新登录"));
    }

    // ── Bind / change email ───────────────────────────────────────

    @PostMapping("/bind-email")
    public ResponseEntity<Map<String, Object>> bindEmail(
            @RequestHeader("X-Student-Token") String token,
            @RequestBody BindEmailRequest req) {
        SessionInfo session = checkSession(token);
        if (session == null) return unauthorized();

        if (req.email() == null || !EMAIL_PATTERN.matcher(req.email()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "邮箱格式不正确"));
        }

        Student student = studentRepository.findByStudentNo(session.studentNo()).orElse(null);
        if (student == null) return ResponseEntity.status(404).body(Map.of("error", "账号不存在"));

        Student existing = studentRepository.findByEmail(req.email()).orElse(null);
        if (existing != null && !existing.getStudentNo().equals(student.getStudentNo())) {
            return ResponseEntity.badRequest().body(Map.of("error", "该邮箱已被其他账号绑定"));
        }

        student.setEmail(req.email());
        student.setEmailVerified(false);
        student.setVerificationToken(TokenGenerator.digest(generateToken()));
        studentRepository.save(student);

        log.info("Email updated for student {}; verification token not logged", student.getStudentNo());

        return ResponseEntity.ok(Map.of("message", "邮箱绑定成功"));
    }

    // ── Delete account ────────────────────────────────────────────

    @DeleteMapping("/delete-account")
    public ResponseEntity<Map<String, Object>> deleteAccount(
            @RequestHeader("X-Student-Token") String token,
            @RequestBody Map<String, String> body) {
        SessionInfo session = checkSession(token);
        if (session == null) return unauthorized();

        Student student = studentRepository.findByStudentNo(session.studentNo()).orElse(null);
        if (student == null) return ResponseEntity.status(404).body(Map.of("error", "账号不存在"));

        // Require password confirmation
        String password = body.get("password");
        if (password == null || !encoder.matches(password, student.getPasswordHash())) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码不正确"));
        }

        // Delete chat history（sessionId = 学号_xxx，前缀匹配，SQL 级删除）
        chatHistoryRepository.deleteBySessionIdStartingWith(student.getStudentNo() + "_");

        // Delete student
        studentRepository.delete(student);
        SessionStore.invalidateByPrincipal("STUDENT", student.getStudentNo());

        log.info("Account deleted: {}", student.getStudentNo());
        return ResponseEntity.ok(Map.of("message", "账号已删除"));
    }

    // ── Data stats ────────────────────────────────────────────────

    @GetMapping("/data-stats")
    public ResponseEntity<Map<String, Object>> dataStats(@RequestHeader("X-Student-Token") String token) {
        SessionInfo session = checkSession(token);
        if (session == null) return unauthorized();

        Student student = studentRepository.findByStudentNo(session.studentNo()).orElse(null);
        if (student == null) return ResponseEntity.status(404).body(Map.of("error", "账号不存在"));

        long chatCount = chatHistoryRepository.countBySessionIdStartingWith(student.getStudentNo() + "_");

        return ResponseEntity.ok(Map.of("chatCount", chatCount));
    }

    // ── Delete all chat data ──────────────────────────────────────

    @DeleteMapping("/delete-chats")
    public ResponseEntity<Map<String, Object>> deleteChats(@RequestHeader("X-Student-Token") String token) {
        SessionInfo session = checkSession(token);
        if (session == null) return unauthorized();

        Student student = studentRepository.findByStudentNo(session.studentNo()).orElse(null);
        if (student == null) return ResponseEntity.status(404).body(Map.of("error", "账号不存在"));

        chatHistoryRepository.deleteBySessionIdStartingWith(student.getStudentNo() + "_");

        return ResponseEntity.ok(Map.of("message", "聊天记录已清空"));
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String getClientIp(HttpServletRequest request) {
        return IpUtils.getClientIp(request);
    }

    private String generateToken() {
        return TokenGenerator.generate();
    }

    private SessionInfo checkSession(String token) {
        SessionStore.Session s = SessionStore.get(token);
        if (s == null) return null;
        SessionStore.touch(token);
        return new SessionInfo(s.principal(), s.ip(), Instant.now());
    }

    private boolean checkRateLimit(String ip) {
        // 周期性清理过期条目（防限流 Map 无限增长）
        if (loginRateMap.size() > 10_000) {
            loginRateMap.entrySet().removeIf(e ->
                    System.currentTimeMillis() - e.getValue().startMs() > LOGIN_RATE_WINDOW_MS);
        }
        RateWindow rw = loginRateMap.get(ip);
        long now = System.currentTimeMillis();
        if (rw != null && now - rw.startMs() < LOGIN_RATE_WINDOW_MS) {
            if (rw.count() >= LOGIN_RATE_LIMIT) {
                log.warn("Login rate limit exceeded for IP {}", ip);
                return false;
            }
            loginRateMap.put(ip, new RateWindow(rw.startMs(), rw.count() + 1));
        } else {
            loginRateMap.put(ip, new RateWindow(now, 1));
        }
        return true;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", (Object)"未登录或会话已过期"));
    }
}
