package com.exambank.chat.controller;

import com.exambank.common.config.SessionStore;
import com.exambank.chat.service.QuestionBankService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 智能题库：AI 出题 / 刷题判分 / 错题本 / 错题讲解（学生端）。 */
@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final QuestionBankService questionBankService;

    public QuizController(QuestionBankService questionBankService) {
        this.questionBankService = questionBankService;
    }

    /** AI 按勾选题库文件出题（topic 可选聚焦主题）。 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody QuestionBankService.GenerateRequest request,
                                      HttpServletRequest httpReq) {
        Long studentId = requireStudentId(httpReq);
        if (studentId == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(questionBankService.generate(studentNo(httpReq), request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "出题失败：" + e.getMessage()));
        }
    }

    /** 题目列表（未作答隐藏答案/解析）。 */
    @GetMapping("/questions")
    public ResponseEntity<?> questions(HttpServletRequest httpReq) {
        Long studentId = requireStudentId(httpReq);
        if (studentId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(questionBankService.listQuestions(studentId));
    }

    /** 提交作答并判分。 */
    @PostMapping("/attempts")
    public ResponseEntity<?> attempt(@RequestBody QuestionBankService.GradeRequest request,
                                     HttpServletRequest httpReq) {
        Long studentId = requireStudentId(httpReq);
        if (studentId == null) return ResponseEntity.status(401).build();
        if (request.questionId() == null || request.answer() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少题目或作答"));
        }
        try {
            return ResponseEntity.ok(questionBankService.grade(studentId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 错题本：最新一次作答为错的题（答对自动移出）。 */
    @GetMapping("/mistakes")
    public ResponseEntity<?> mistakes(HttpServletRequest httpReq) {
        Long studentId = requireStudentId(httpReq);
        if (studentId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(questionBankService.mistakes(studentId));
    }

    /** 错题 AI 针对性讲解。 */
    @PostMapping("/explain")
    public ResponseEntity<?> explain(@RequestBody QuestionBankService.ExplainRequest request,
                                     HttpServletRequest httpReq) {
        Long studentId = requireStudentId(httpReq);
        if (studentId == null) return ResponseEntity.status(401).build();
        if (request.questionId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少题目"));
        }
        try {
            String text = questionBankService.explainWrong(studentId, request);
            return ResponseEntity.ok(Map.of("explanation", text));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "讲解失败：" + e.getMessage()));
        }
    }

    // ── 认证（与 ChatController 同模式）──

    private Long requireStudentId(HttpServletRequest req) {
        String studentNo = studentNo(req);
        if (studentNo == null) return null;
        try {
            return questionBankService.resolveStudentId(studentNo);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String studentNo(HttpServletRequest req) {
        SessionStore.Session session = SessionStore.get(getToken(req));
        if (session == null || !"STUDENT".equals(session.role())) return null;
        return session.principal();
    }

    private String getToken(HttpServletRequest req) {
        String t = req.getHeader("X-Auth-Token");
        if (t == null || t.isEmpty()) t = req.getHeader("X-Student-Token");
        return t;
    }
}
