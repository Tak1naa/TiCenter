package com.exambank.common.security;

import com.exambank.common.dto.ContextRef;
import com.exambank.common.dto.Reference;
import com.exambank.common.entity.ExamFile;
import com.exambank.common.entity.TextChunk;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.common.repository.TextChunkRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class DocumentContextService {
    private final ExamFileRepository files;
    private final TextChunkRepository chunks;

    public DocumentContextService(ExamFileRepository files, TextChunkRepository chunks) {
        this.files = files;
        this.chunks = chunks;
    }

    public record Resolved(List<TextChunk> chunks, List<Reference> references) {}

    public Resolved resolve(List<ContextRef> refs, List<Long> selectedIds, String courseId) {
        if (refs == null || refs.isEmpty()) return new Resolved(List.of(), List.of());
        if (refs.size() > 8) throw invalid("最多附加 8 个上下文");
        List<TextChunk> result = new ArrayList<>();
        List<Reference> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ContextRef ref : refs) {
            if (ref == null || ref.fileId() == null || ref.generationId() == null || ref.page() < 1)
                throw invalid("无效的来源位置");
            ExamFile file = files.findById(ref.fileId())
                    .filter(f -> FileAccessPolicy.PUBLIC.equals(f.getAccessScope()))
                    .orElseThrow(() -> invalid("来源不可用"));
            if (selectedIds != null && !selectedIds.isEmpty() && !selectedIds.contains(file.getId()))
                throw invalid("来源不在所选文件中");
            if (courseId != null && !courseId.isBlank() && !courseId.equals(file.getCourseId()))
                throw invalid("来源不在所选科目中");
            if (!Objects.equals(ref.generationId(), file.getActiveGenerationId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "CONTEXT_VERSION_CHANGED：文件已更新，请重新选择");
            List<TextChunk> page = chunks.findByFileIdAndGenerationIdAndPageNumberOrderByChunkIndex(
                    file.getId(), file.getActiveGenerationId(), ref.page());
            if (page.isEmpty()) throw invalid("该页尚无可用解析内容");
            String text = page.stream().map(TextChunk::getContent).filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            String quote = ref.quote();
            if ("selection".equals(ref.kind())) {
                if (quote == null || quote.isBlank()) throw invalid("文字选区为空");
                // Whitespace may differ between the PDF text layer and the parser, but words must match.
                String normalized = text.replaceAll("\\s+", "");
                String selected = quote.replaceAll("\\s+", "");
                int at = normalized.indexOf(selected);
                if (selected.isEmpty() || at < 0 || normalized.indexOf(selected, at + 1) >= 0)
                    throw invalid("选区无法唯一对应原文，请改用整页");
                text = quote;
            } else if ("region".equals(ref.kind())) {
                List<Double> b = ref.bbox();
                if (b == null || b.size() != 4 || b.stream().anyMatch(v -> v == null || !Double.isFinite(v) || v < 0 || v > 1)
                        || b.get(0) >= b.get(2) || b.get(1) >= b.get(3)) throw invalid("无效的框选坐标");
                // Without verified OCR block geometry, do not pretend text was extracted from the box.
            } else if (!"page".equals(ref.kind())) throw invalid("无效的上下文类型");
            String key = file.getId() + ":" + ref.page() + ":" + text;
            if (!seen.add(key)) continue;
            Reference source = new Reference(file.getFileName(), ref.page(), text, file.getFilePath());
            source.setSourceId("S" + (sources.size() + 1));
            source.setFileId(file.getId());
            source.setChunkId(page.getFirst().getId());
            source.setGenerationId(file.getActiveGenerationId());
            source.setDocumentVersion(file.getDocumentVersion());
            source.setAccessScope(FileAccessPolicy.PUBLIC);
            source.setContextKind(ref.kind());
            source.setBbox(ref.bbox());
            source.setLocationPrecision("selection".equals(ref.kind()) ? "text" : "page");
            sources.add(source);
            result.addAll(page);
        }
        return new Resolved(List.copyOf(result), List.copyOf(sources));
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT_REF：" + message);
    }
}
