package com.exambank.common.repository;

import com.exambank.common.entity.ExamFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ExamFileRepository extends JpaRepository<ExamFile, Long> {
    Optional<ExamFile> findByFilePath(String filePath);
    List<ExamFile> findByAccessScope(String accessScope);
    List<ExamFile> findByCourseIdAndAccessScope(String courseId, String accessScope);
    List<ExamFile> findByParseStatus(String status);
    List<ExamFile> findByFilePathStartingWith(String directoryPath);
    boolean existsByFilePathAndFileHash(String filePath, String fileHash);

    /** 按状态+时间统计（监控/告警用，替代 findAll + Java 过滤）。 */
    long countByParseStatusAndUpdatedAtAfter(String parseStatus, LocalDateTime updatedAt);

    // ── Admin 文件列表：搜索/状态过滤下推到 SQL（替代先分页后 Java 过滤）──
    Page<ExamFile> findByFileNameContaining(String fileName, Pageable pageable);
    Page<ExamFile> findByParseStatus(String parseStatus, Pageable pageable);
    Page<ExamFile> findByFileNameContainingAndParseStatus(String fileName, String parseStatus, Pageable pageable);

    /** Cross-process reparse claim. A dead worker's claim becomes reclaimable after the supplied cutoff. */
    @Modifying
    @Transactional
    @Query("update ExamFile f set f.parseClaimToken=:token, f.parseClaimedAt=:now " +
            "where f.id=:fileId and (f.parseClaimToken is null or f.parseClaimedAt < :staleBefore)")
    int claimParse(@Param("fileId") Long fileId, @Param("token") String token,
                   @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);

    @Modifying
    @Transactional
    @Query("update ExamFile f set f.parseClaimToken=null, f.parseClaimedAt=null " +
            "where f.id=:fileId and f.parseClaimToken=:token")
    int releaseParseClaim(@Param("fileId") Long fileId, @Param("token") String token);
}
