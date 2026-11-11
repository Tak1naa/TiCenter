package com.exambank.chat.service;

import com.exambank.common.entity.ExamFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/** Central cache-key schema; access scope and active generations are intentionally mandatory. */
final class RetrievalCacheKey {
    private RetrievalCacheKey() {}

    static String build(String question, List<ExamFile> files, String courseId,
                        String embeddingModel, String rerankerModel, int topK, String promptVersion) {
        String normalized = question == null ? "" : question.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String generations = files.stream().sorted(Comparator.comparing(ExamFile::getId))
                .map(file -> file.getId() + ":" + Objects.toString(file.getActiveGenerationId(), "none"))
                .collect(Collectors.joining(","));
        return hash(normalized) + "|" + generations + "|" + Objects.toString(courseId, "")
                + "|PUBLIC|" + embeddingModel + "|" + rerankerModel + "|" + topK + "|" + promptVersion;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }
}
