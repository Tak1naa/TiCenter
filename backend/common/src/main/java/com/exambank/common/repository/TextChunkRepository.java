package com.exambank.common.repository;

import com.exambank.common.entity.TextChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Collection;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface TextChunkRepository extends JpaRepository<TextChunk, Long> {
    List<TextChunk> findByFileIdOrderByChunkIndex(Long fileId);
    List<TextChunk> findByFileIdAndGenerationIdOrderByChunkIndex(Long fileId, String generationId);
    List<TextChunk> findByGenerationIdOrderByChunkIndex(String generationId);
    List<TextChunk> findByFileIdAndGenerationIdAndPageNumberOrderByChunkIndex(Long fileId, String generationId, Integer pageNumber);

    @Modifying
    @Transactional
    void deleteByFileId(Long fileId);

    @Modifying
    @Transactional
    void deleteByGenerationId(String generationId);

    /** 已索引 chunk 数（milvusId 非空），替代 findAll + Java 过滤。 */
    long countByMilvusIdIsNotNull();
    long countByIndexStatus(String indexStatus);
    List<TextChunk> findTop500ByIndexStatusInOrderByIdAsc(Collection<String> statuses);
}
