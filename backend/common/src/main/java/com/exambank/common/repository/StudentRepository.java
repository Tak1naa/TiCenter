package com.exambank.common.repository;

import com.exambank.common.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByStudentNo(String studentNo);
    Optional<Student> findByEmail(String email);
    Optional<Student> findByResetToken(String resetToken);
    Optional<Student> findByVerificationToken(String token);
    long countByStatus(String status);

    /** Admin 学生列表搜索（学号/邮箱 LIKE，下推到 SQL）。 */
    Page<Student> findByStudentNoContainingOrEmailContaining(String studentNo, String email, Pageable pageable);
}
