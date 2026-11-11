package com.exambank.chat.service;

import com.exambank.common.entity.ExamFile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalCacheKeyTest {
    @Test
    void orderIsStableButEveryIsolationAndVersionDimensionInvalidates() {
        ExamFile a = file(1, "g1"), b = file(2, "g2");
        String baseline = key("问题", List.of(a, b), "course", "embed-v1", "rerank-v1", 20, "prompt-v2");
        assertThat(key("  问题  ", List.of(b, a), "course", "embed-v1", "rerank-v1", 20, "prompt-v2"))
                .isEqualTo(baseline);
        a.setActiveGenerationId("g1-new");
        assertThat(key("问题", List.of(a, b), "course", "embed-v1", "rerank-v1", 20, "prompt-v2"))
                .isNotEqualTo(baseline);
        a.setActiveGenerationId("g1");
        assertThat(key("问题", List.of(a, b), "other", "embed-v1", "rerank-v1", 20, "prompt-v2"))
                .isNotEqualTo(baseline);
        assertThat(key("问题", List.of(a, b), "course", "embed-v2", "rerank-v1", 20, "prompt-v2"))
                .isNotEqualTo(baseline);
        assertThat(key("问题", List.of(a, b), "course", "embed-v1", "rerank-v1", 20, "prompt-v3"))
                .isNotEqualTo(baseline);
    }

    private String key(String q, List<ExamFile> files, String course, String embed,
                       String rerank, int topK, String prompt) {
        return RetrievalCacheKey.build(q, files, course, embed, rerank, topK, prompt);
    }

    private ExamFile file(long id, String generation) {
        ExamFile file = new ExamFile(); file.setId(id); file.setActiveGenerationId(generation); return file;
    }
}
