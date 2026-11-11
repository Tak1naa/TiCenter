package com.exambank.chat.controller;

import com.exambank.chat.aop.Auditable;
import com.exambank.common.repository.QuestionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/questions")
public class AdminQuestionReviewController {
    private final QuestionRepository repository;
    public AdminQuestionReviewController(QuestionRepository repository) { this.repository = repository; }

    @GetMapping("/pending")
    public ResponseEntity<?> pending(@RequestHeader("X-Admin-Token") String token) {
        if (AdminAuthController.checkSession(token) == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(repository.findTop200ByReviewStatusOrderByCreatedAtDesc("PENDING"));
    }

    @PutMapping("/{id}/review")
    @Auditable(action = "REVIEW_AI_QUESTION")
    public ResponseEntity<?> review(@RequestHeader("X-Admin-Token") String token,
                                    @PathVariable Long id, @RequestBody Map<String, String> body) {
        if (AdminAuthController.checkWriteRole(token) == null) return ResponseEntity.status(403).build();
        String status = body.getOrDefault("status", "").toUpperCase();
        if (!status.equals("APPROVED") && !status.equals("REJECTED"))
            return ResponseEntity.badRequest().body(Map.of("error", "status 必须为 APPROVED 或 REJECTED"));
        return repository.findById(id).map(question -> {
            question.setReviewStatus(status);
            repository.save(question);
            return ResponseEntity.ok(Map.of("id", id, "status", status));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
