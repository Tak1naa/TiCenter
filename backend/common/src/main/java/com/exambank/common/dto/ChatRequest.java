package com.exambank.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ChatRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题过长（最大2000字符）")
        String question,
        @Size(max = 64) String sessionId,
        @Size(max = 100, message = "最多选择100个文件")
        List<Long> selectedFileIds,
        List<String> selectedFiles,
        @Size(max = 64) String courseId,
        @Size(max = 64) String provider,
        @Size(max = 128) String model,
        @Size(max = 256) String apiKey,
        @Size(max = 256) String baseUrl,
        @Max(value = 16384, message = "maxTokens 不能超过 16384")
        Integer maxTokens,
        Boolean multimodal,
        @jakarta.validation.Valid @Size(max = 8) List<ContextRef> contextRefs) {
    public ChatRequest() { this(null, null, null, null, null, null, null, null, null, null, null, null); }
}
