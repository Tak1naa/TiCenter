package com.exambank.chat.controller;

import com.exambank.chat.aop.Auditable;
import com.exambank.common.entity.Student;
import com.exambank.common.repository.ChatHistoryRepository;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.common.repository.StudentRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/data")
public class AdminDataController {

    private final ChatHistoryRepository chatHistoryRepository;
    private final ExamFileRepository examFileRepository;
    private final StudentRepository studentRepository;

    public AdminDataController(ChatHistoryRepository chatHistoryRepository,
                               ExamFileRepository examFileRepository,
                               StudentRepository studentRepository) {
        this.chatHistoryRepository = chatHistoryRepository;
        this.examFileRepository = examFileRepository;
        this.studentRepository = studentRepository;
    }

    @GetMapping("/export/students")
    public void exportStudents(@RequestHeader("X-Admin-Token") String token,
                                HttpServletResponse response) throws Exception {
        if (AdminAuthController.checkSession(token) == null) {
            response.setStatus(401);
            return;
        }

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=students.csv");
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(0xEF);
        response.getOutputStream().write(0xBB);
        response.getOutputStream().write(0xBF);

        PrintWriter w = response.getWriter();
        w.println("ID,学号,邮箱,邮箱验证,状态,注册时间,最后登录");
        int pageNumber = 0;
        org.springframework.data.domain.Page<Student> page;
        do {
            page = studentRepository.findAll(org.springframework.data.domain.PageRequest.of(pageNumber++, 500,
                    org.springframework.data.domain.Sort.by("id")));
            for (Student s : page.getContent()) w.printf("%d,%s,%s,%s,%s,%s,%s%n",
                    s.getId(), csv(s.getStudentNo()),
                    csv(s.getEmail()),
                    s.isEmailVerified() ? "是" : "否",
                    csv(s.getStatus()), s.getCreatedAt(), s.getLastLoginAt());
        } while (page.hasNext());
        w.flush();
    }

    @GetMapping("/export/chats")
    public void exportChats(@RequestHeader("X-Admin-Token") String token,
                             HttpServletResponse response) throws Exception {
        if (AdminAuthController.checkSession(token) == null) {
            response.setStatus(401);
            return;
        }

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=chats.csv");
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(0xEF);
        response.getOutputStream().write(0xBB);
        response.getOutputStream().write(0xBF);

        PrintWriter w = response.getWriter();
        w.println("ID,会话ID,角色,内容,创建时间");
        int pageNumber = 0;
        org.springframework.data.domain.Page<com.exambank.common.entity.ChatHistory> page;
        do {
            page = chatHistoryRepository.findAll(org.springframework.data.domain.PageRequest.of(pageNumber++, 500,
                    org.springframework.data.domain.Sort.by("id")));
            for (var c : page.getContent()) {
                String content = c.getContent() != null ? c.getContent() : "";
                if (content.length() > 500) content = content.substring(0, 500) + "...";
                w.printf("%d,%s,%s,%s,%s%n", c.getId(), csv(c.getSessionId()), csv(c.getRole()),
                        csv(content), c.getCreatedAt());
            }
        } while (page.hasNext());
        w.flush();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    @DeleteMapping("/batch/chats")
    @Auditable(action = "BATCH_DELETE_CHATS")
    public ResponseEntity<Map<String, String>> deleteChats(@RequestHeader("X-Admin-Token") String token,
                                                            @RequestBody Map<String, List<Long>> body) {
        if (AdminAuthController.checkAdminRole(token) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }
        List<Long> ids = body.getOrDefault("ids", List.of());
        if (ids.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "未选择记录"));
        chatHistoryRepository.deleteAllById(ids);
        return ResponseEntity.ok(Map.of("message", "已删除 " + ids.size() + " 条记录"));
    }

    @DeleteMapping("/batch/files")
    @Auditable(action = "BATCH_DELETE_FILES")
    public ResponseEntity<Map<String, String>> deleteFiles(@RequestHeader("X-Admin-Token") String token,
                                                            @RequestBody Map<String, List<Long>> body) {
        if (AdminAuthController.checkAdminRole(token) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }
        List<Long> ids = body.getOrDefault("ids", List.of());
        if (ids.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "未选择文件"));
        examFileRepository.deleteAllById(ids);
        return ResponseEntity.ok(Map.of("message", "已删除 " + ids.size() + " 个文件"));
    }
}
