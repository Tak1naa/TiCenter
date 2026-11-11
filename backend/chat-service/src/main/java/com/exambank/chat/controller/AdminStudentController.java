package com.exambank.chat.controller;

import com.exambank.chat.aop.AuditAspect;
import com.exambank.chat.aop.Auditable;
import com.exambank.common.entity.AuditLog;
import com.exambank.common.entity.Student;
import com.exambank.common.repository.AuditLogRepository;
import com.exambank.common.repository.StudentRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/students")
public class AdminStudentController {

    private static final Logger log = LoggerFactory.getLogger(AdminStudentController.class);

    private final StudentRepository studentRepository;
    private final AuditLogRepository auditLogRepository;

    public AdminStudentController(StudentRepository studentRepository,
                                  AuditLogRepository auditLogRepository) {
        this.studentRepository = studentRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("X-Admin-Token") String token,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   @RequestParam(defaultValue = "") String search) {
        if (!checkAdmin(token)) return unauthorized();
        size = Math.min(Math.max(size, 1), 100);
        // 搜索下推到 SQL（先过滤后分页，翻页与 total 才正确）
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Student> all = (search == null || search.isBlank())
                ? studentRepository.findAll(pageable).getContent()
                : studentRepository.findByStudentNoContainingOrEmailContaining(search, search, pageable).getContent();

        List<Map<String, Object>> result = all.stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("studentNo", s.getStudentNo());
                    m.put("email", s.getEmail() != null ? s.getEmail() : "");
                    m.put("emailVerified", s.isEmailVerified());
                    m.put("status", s.getStatus());
                    m.put("createdAt", s.getCreatedAt().toString());
                    m.put("lastLoginAt", s.getLastLoginAt() != null ? s.getLastLoginAt().toString() : null);
                    return m;
                }).toList();

        return ResponseEntity.ok(Map.of("items", result, "total", studentRepository.count()));
    }

    @PostMapping("/{studentNo}/disable")
    @Auditable(action = "DISABLE_STUDENT")
    public ResponseEntity<Map<String, Object>> disable(@RequestHeader("X-Admin-Token") String token,
                                                        @PathVariable String studentNo,
                                                        HttpServletRequest request) {
        if (!checkAdminWrite(token)) return unauthorized();

        Student s = studentRepository.findByStudentNo(studentNo).orElse(null);
        if (s == null) return ResponseEntity.status(404).body(Map.of("error", "学生不存在"));
        s.setStatus("DISABLED");
        studentRepository.save(s);
        log.info("Student {} disabled by admin", studentNo);
        return ResponseEntity.ok(Map.of("message", "已禁用"));
    }

    @PostMapping("/{studentNo}/enable")
    @Auditable(action = "ENABLE_STUDENT")
    public ResponseEntity<Map<String, Object>> enable(@RequestHeader("X-Admin-Token") String token,
                                                       @PathVariable String studentNo) {
        if (!checkAdminWrite(token)) return unauthorized();

        Student s = studentRepository.findByStudentNo(studentNo).orElse(null);
        if (s == null) return ResponseEntity.status(404).body(Map.of("error", "学生不存在"));
        s.setStatus("ACTIVE");
        studentRepository.save(s);
        log.info("Student {} enabled by admin", studentNo);
        return ResponseEntity.ok(Map.of("message", "已启用"));
    }

    @DeleteMapping("/{studentNo}")
    @Auditable(action = "DELETE_STUDENT")
    public ResponseEntity<Map<String, Object>> delete(@RequestHeader("X-Admin-Token") String token,
                                                       @PathVariable String studentNo) {
        if (!checkAdminWrite(token)) return unauthorized();

        Student s = studentRepository.findByStudentNo(studentNo).orElse(null);
        if (s == null) return ResponseEntity.status(404).body(Map.of("error", "学生不存在"));
        studentRepository.delete(s);
        log.info("Student {} deleted by admin", studentNo);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(@RequestHeader("X-Admin-Token") String token) {
        if (!checkAdmin(token)) return unauthorized();

        long total = studentRepository.count();
        long active = studentRepository.countByStatus("ACTIVE");
        long disabled = studentRepository.countByStatus("DISABLED");

        return ResponseEntity.ok(Map.of("total", total, "active", active, "disabled", disabled));
    }

    private boolean checkAdmin(String token) {
        return AdminAuthController.checkSession(token) != null;
    }

    /** 写操作：VIEWER 无权限 */
    private boolean checkAdminWrite(String token) {
        return AdminAuthController.checkAdminRole(token) != null;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", (Object)"未登录或会话已过期"));
    }
}
