package com.exambank.document.service;

import com.exambank.common.entity.ExamFile;
import com.exambank.common.entity.TextChunk;
import com.exambank.common.dto.ParseResult;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.common.repository.TextChunkRepository;
import com.exambank.common.entity.ParseJob;
import com.exambank.common.repository.ParseJobRepository;
import com.exambank.common.entity.DocumentGeneration;
import com.exambank.common.repository.DocumentGenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ParseTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(ParseTaskScheduler.class);

    private final ExamFileRepository examFileRepository;
    private final TextChunkRepository textChunkRepository;
    private final ParseJobRepository parseJobRepository;
    private final DocumentGenerationRepository generationRepository;
    private final TransactionTemplate transactions;
    private final RestTemplate restTemplate = createRestTemplate();

    /** 服务间共享 token（与 Python 服务鉴权一致，环境变量注入）。 */
    private static final String API_SERVICE_TOKEN = System.getenv("API_SERVICE_TOKEN") != null
            ? System.getenv("API_SERVICE_TOKEN") : "";

    private static RestTemplate createRestTemplate() {
        RestTemplate rt = new RestTemplate();
        if (!API_SERVICE_TOKEN.isEmpty()) {
            rt.getInterceptors().add((req, body, execution) -> {
                req.getHeaders().set("X-Api-Token", API_SERVICE_TOKEN);
                return execution.execute(req, body);
            });
        }
        return rt;
    }

    @Value("${app.ocr.service-url:http://localhost:8001}")
    private String ocrServiceUrl;

    @Value("${app.vector.service-url:http://localhost:8082}")
    private String vectorServiceUrl;

    @Value("${app.parse.paused:false}")
    private boolean paused;
    @Value("${app.retrieval.backend:llamaindex}")
    private String retrievalBackend;
    @Value("${app.intelligence.service-url:http://localhost:8004}")
    private String intelligenceUrl;

    /** 解析并发上限：OCR 服务（LibreOffice 子进程 / PaddleOCR）很吃 CPU/内存，
     *  启动扫库/批量上传时同时解析全部文件会打满机器。默认最多 2 个并发。 */
    private static final java.util.concurrent.Semaphore PARSE_SEMAPHORE =
            new java.util.concurrent.Semaphore(2);
    private static final String CHUNKER_VERSION = "structure-v2-1600-160";
    private static final String EMBEDDING_MODEL = System.getenv().getOrDefault("EMBED_MODEL", "BAAI/bge-m3");

    public ParseTaskScheduler(ExamFileRepository examFileRepository,
                              TextChunkRepository textChunkRepository,
                              ParseJobRepository parseJobRepository,
                              DocumentGenerationRepository generationRepository,
                              PlatformTransactionManager transactionManager) {
        this.examFileRepository = examFileRepository;
        this.textChunkRepository = textChunkRepository;
        this.parseJobRepository = parseJobRepository;
        this.generationRepository = generationRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void submit(ExamFile file) {
        if (parseJobRepository.existsByFileIdAndStatusIn(file.getId(), Set.of("PENDING", "RUNNING"))) return;
        ParseJob job = new ParseJob();
        job.setId(UUID.randomUUID().toString());
        job.setFileId(file.getId());
        parseJobRepository.save(job);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.parse.poll-ms:1000}")
    public void poll() {
        if (paused) return;
        for (ParseJob job : parseJobRepository
                .findTop10ByStatusAndNextRunAtBeforeOrderByCreatedAtAsc("PENDING", LocalDateTime.now())) {
            if (!PARSE_SEMAPHORE.tryAcquire()) return;
            if (parseJobRepository.claim(job.getId(), LocalDateTime.now()) != 1) {
                PARSE_SEMAPHORE.release();
                continue;
            }
            Thread.ofVirtual().start(() -> runJob(job.getId()));
        }
    }

    private void runJob(String jobId) {
        String parseClaim = UUID.randomUUID().toString();
        Long claimedFileId = null;
        try {
            ParseJob job = parseJobRepository.findById(jobId).orElseThrow();
            claimedFileId = job.getFileId();
            LocalDateTime now = LocalDateTime.now();
            if (examFileRepository.claimParse(claimedFileId, parseClaim, now, now.minusMinutes(30)) != 1) {
                throw new IllegalStateException("该文件已有活动重解析任务");
            }
            processParseTask(job.getFileId());
            job.setStatus("COMPLETED");
            job.setLastError(null);
            parseJobRepository.save(job);
        } catch (Exception e) {
            parseJobRepository.findById(jobId).ifPresent(job -> {
                job.setLastError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().substring(0, Math.min(1000, e.getMessage().length())));
                if (job.getAttempts() >= 3) {
                    job.setStatus("DEAD");
                } else {
                    job.setStatus("PENDING");
                    long minutes = job.getAttempts() == 1 ? 1 : job.getAttempts() == 2 ? 5 : 30;
                    job.setNextRunAt(LocalDateTime.now().plusMinutes(minutes));
                }
                parseJobRepository.save(job);
            });
        } finally {
            if (claimedFileId != null) examFileRepository.releaseParseClaim(claimedFileId, parseClaim);
            PARSE_SEMAPHORE.release();
        }
    }

    private void markFailed(ExamFile file, String message) {
        try {
            file.setParseStatus("failed");
            file.setErrorMessage(message);
            examFileRepository.save(file);
        } catch (Exception ex) {
            log.error("Failed to update error status", ex);
        }
    }

    public void processParseTask(Long fileId) {
        ExamFile file = examFileRepository.findById(fileId).orElse(null);
        if (file == null) {
            return;
        }

        String previousStatus = file.getParseStatus();
        String previousGenerationId = file.getActiveGenerationId();
        String stagingGenerationId = null;
        boolean qdrantActivated = false;
        try {
            file.setParseStatus("processing");
            examFileRepository.saveAndFlush(file);

            ParseResult result = restTemplate.postForObject(
                    ocrServiceUrl + "/parse",
                    new ParseRequestRecord(file.getFilePath(), false),
                    ParseResult.class
            );

            if (result == null) {
                throw new RuntimeException("OCR service returned null");
            }

            // Build a staging generation. The active generation remains untouched until vectors are verified.
            List<TextChunk> newChunks = new ArrayList<>();
            int globalChunkIndex = 0;
            for (ParseResult.PageResult page : result.getPages()) {
                List<ChunkPiece> chunks = splitPage(page.getText());
                for (ChunkPiece piece : chunks) {
                    if (piece.text().isBlank()) continue;
                    TextChunk chunk = new TextChunk();
                    chunk.setFileId(file.getId());
                    chunk.setChunkIndex(globalChunkIndex++);
                    chunk.setContent(piece.text().trim());
                    chunk.setPageNumber(page.getPageNumber());
                    chunk.setChunkHash(sha256(piece.text().trim()));
                    chunk.setStructureType(piece.type());
                    chunk.setIndexStatus("pending");
                    newChunks.add(chunk);
                }
            }
            if (newChunks.isEmpty()) throw new IllegalStateException("解析结果没有可索引文本");
            String documentVersion = result.getFileHash() == null || result.getFileHash().isBlank()
                    ? sha256(newChunks.stream().map(TextChunk::getChunkHash).collect(java.util.stream.Collectors.joining("|")))
                    : result.getFileHash();
            String chunkerVersion = "llamaindex".equals(retrievalBackend) ? "llamaindex-sentence-600-75-v1" : CHUNKER_VERSION;
            String generationId = sha256(file.getId() + "|" + documentVersion + "|" + result.getParseMethod() + "|" + chunkerVersion + "|"
                    + newChunks.stream().map(TextChunk::getChunkHash).collect(java.util.stream.Collectors.joining("|")));
            stagingGenerationId = generationId;
            newChunks.forEach(chunk -> chunk.setGenerationId(generationId));

            DocumentGeneration already = generationRepository.findById(generationId).orElse(null);
            if (already != null && "ACTIVE".equals(already.getStatus())
                    && generationId.equals(previousGenerationId)) {
                file.setParseStatus("completed");
                file.setErrorMessage(null);
                examFileRepository.save(file);
                return;
            }

            if (already != null && !"ACTIVE".equals(already.getStatus())) {
                generationRepository.delete(already); // cascades stale staging chunks; deterministic retry
                generationRepository.flush();
            }
            DocumentGeneration generation = new DocumentGeneration();
            generation.setGenerationId(generationId);
            generation.setFileId(file.getId());
            generation.setDocumentVersion(documentVersion);
            generation.setChunkerVersion(chunkerVersion);
            generation.setEmbeddingModel(EMBEDDING_MODEL);
            generation.setExpectedChunkCount(newChunks.size());
            generationRepository.saveAndFlush(generation);
            newChunks = textChunkRepository.saveAll(newChunks);
            textChunkRepository.flush();
            // First block of each page is the parent context; children point to it.
            java.util.Map<Integer, Long> pageParents = new java.util.HashMap<>();
            for (TextChunk chunk : newChunks) {
                int page = chunk.getPageNumber() == null ? 1 : chunk.getPageNumber();
                pageParents.putIfAbsent(page, chunk.getId());
                Long parentId = pageParents.get(page);
                if (!java.util.Objects.equals(parentId, chunk.getId())) chunk.setParentChunkId(parentId);
            }
            textChunkRepository.saveAll(newChunks);
            int chunkCount = newChunks.size();

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> indexed = restTemplate.postForObject(vectorServiceUrl + "/api/vector/index-chunks",
                    java.util.Map.of("chunkIds", newChunks.stream().map(TextChunk::getId).toList()), java.util.Map.class);
            int indexedCount = indexed == null ? 0 : ((Number) indexed.getOrDefault("indexed", 0)).intValue();
            if (indexedCount != chunkCount) throw new IllegalStateException(
                    "向量索引数量不一致: expected=" + chunkCount + ", actual=" + indexedCount);
            generation.setIndexedCount(indexedCount);
            generation.setStatus("INDEXED");
            generationRepository.saveAndFlush(generation);

            restTemplate.postForObject(vectorServiceUrl + "/api/vector/generations/activate",
                    java.util.Map.of("fileId", file.getId(), "generationId", generationId,
                            "expectedCount", chunkCount), java.util.Map.class);
            qdrantActivated = true;

            String oldGenerationId = previousGenerationId;
            transactions.executeWithoutResult(status -> {
                ExamFile current = examFileRepository.findById(file.getId()).orElseThrow();
                current.setParseMethod(result.getParseMethod());
                current.setTotalPages(result.getTotalPages());
                current.setParsedPages(result.getPages().size());
                current.setParseStatus("completed");
                current.setParsedAt(LocalDateTime.now());
                current.setFileHash(result.getFileHash());
                current.setDocumentVersion(documentVersion);
                current.setActiveGenerationId(generationId);
                current.setErrorMessage(null);
                examFileRepository.save(current);
                DocumentGeneration fresh = generationRepository.findById(generationId).orElseThrow();
                fresh.setStatus("ACTIVE");
                fresh.setActivatedAt(LocalDateTime.now());
                generationRepository.save(fresh);
                if (oldGenerationId != null && !oldGenerationId.equals(generationId)) {
                    generationRepository.findById(oldGenerationId).ifPresent(old -> {
                        old.setStatus("CLEANUP_PENDING");
                        old.setRetiredAt(LocalDateTime.now());
                        generationRepository.save(old);
                    });
                }
            });

            if (oldGenerationId != null && !oldGenerationId.equals(generationId)) {
                try {
                    cleanupGeneration(oldGenerationId, file.getId());
                } catch (Exception cleanupError) {
                    log.warn("Generation cleanup deferred for {}: {}", oldGenerationId, cleanupError.getMessage());
                }
            }

            log.info("Parsed file {} successfully, method={}, pages={}, chunks={}",
                    file.getFileName(), result.getParseMethod(),
                    result.getTotalPages(), chunkCount);

        } catch (Exception e) {
            log.error("Failed to parse file: {}", file.getFilePath(), e);
            // Qdrant activation precedes the MySQL pointer switch. If that transaction failed,
            // remove the uncommitted generation so only the old DB generation can be served.
            if (qdrantActivated && stagingGenerationId != null
                    && !stagingGenerationId.equals(examFileRepository.findById(fileId)
                    .map(ExamFile::getActiveGenerationId).orElse(null))) {
                try {
                    restTemplate.postForObject(vectorServiceUrl + "/api/vector/generations/cleanup",
                            java.util.Map.of("fileId", fileId, "generationId", stagingGenerationId), java.util.Map.class);
                } catch (Exception compensationError) {
                    log.error("Failed to compensate uncommitted Qdrant generation {}", stagingGenerationId,
                            compensationError);
                }
            }
            if (stagingGenerationId != null) {
                String failedGenerationId = stagingGenerationId;
                generationRepository.findById(failedGenerationId).ifPresent(generation -> {
                    if (!"ACTIVE".equals(generation.getStatus())) {
                        generation.setStatus("FAILED");
                        generation.setError(e.getMessage() == null ? e.getClass().getSimpleName()
                                : e.getMessage().substring(0, Math.min(1000, e.getMessage().length())));
                        generationRepository.save(generation);
                    }
                });
            }
            // A failed reparse must not take the previously active generation offline.
            ExamFile current = examFileRepository.findById(fileId).orElseThrow();
            current.setParseStatus(current.getActiveGenerationId() == null ? "failed" : "completed");
            current.setErrorMessage(e.getMessage());
            examFileRepository.saveAndFlush(current);
            throw new IllegalStateException("解析失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ChunkPiece> splitPage(String text) {
        if (!"llamaindex".equals(retrievalBackend)) return chunkText(text, 1600, 160);
        java.util.Map<String, Object> result = restTemplate.postForObject(intelligenceUrl + "/internal/split",
                java.util.Map.of("text", text == null ? "" : text), java.util.Map.class);
        if (result == null) throw new IllegalStateException("split service unavailable");
        List<java.util.Map<String, String>> chunks = (List<java.util.Map<String, String>>) result.get("chunks");
        return chunks.stream().map(c -> new ChunkPiece(c.get("text"), c.getOrDefault("type", "paragraph"))).toList();
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.parse.cleanup-ms:30000}")
    public void retryGenerationCleanup() {
        if (paused) return;
        for (DocumentGeneration generation : generationRepository
                .findTop50ByStatusOrderByRetiredAtAsc("CLEANUP_PENDING")) {
            try {
                cleanupGeneration(generation.getGenerationId(), generation.getFileId());
            } catch (Exception e) {
                log.warn("Generation cleanup deferred for {}: {}", generation.getGenerationId(), e.getMessage());
            }
        }
    }

    private void cleanupGeneration(String generationId, Long fileId) {
        restTemplate.postForObject(vectorServiceUrl + "/api/vector/generations/cleanup",
                java.util.Map.of("fileId", fileId, "generationId", generationId), java.util.Map.class);
        transactions.executeWithoutResult(status -> {
            textChunkRepository.deleteByGenerationId(generationId);
            generationRepository.findById(generationId).ifPresent(old -> {
                old.setStatus("RETIRED");
                old.setError(null);
                generationRepository.save(old);
            });
        });
    }

    /** Structure-aware page-local chunks. maxChars approximates 300-800 mixed-language tokens. */
    private List<ChunkPiece> chunkText(String text, int maxChars, int overlapChars) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.replace("\r\n", "\n").strip();
        List<ChunkPiece> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentType = "paragraph";
        for (String rawBlock : normalized.split("\n{2,}")) {
            String block = rawBlock.strip();
            if (block.isEmpty()) continue;
            String type = structureType(block);
            boolean boundary = "heading".equals(type) || "question".equals(type)
                    || "table".equals(type) || "formula".equals(type);
            if (!current.isEmpty() && (boundary || current.length() + block.length() + 2 > maxChars)) {
                appendSplit(result, current.toString(), currentType, maxChars, overlapChars);
                String overlap = tail(current.toString(), overlapChars);
                current = new StringBuilder(overlap);
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(block);
            if (current.length() == block.length()) currentType = type;
        }
        if (!current.isEmpty()) appendSplit(result, current.toString(), currentType, maxChars, overlapChars);
        return result;
    }

    private void appendSplit(List<ChunkPiece> out, String text, String type, int maxChars, int overlapChars) {
        if (text.length() <= maxChars) {
            out.add(new ChunkPiece(text, type));
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                int sentence = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('\n', end));
                if (sentence > start + maxChars / 2) end = sentence + 1;
            }
            out.add(new ChunkPiece(text.substring(start, end).strip(), type));
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - overlapChars);
        }
    }

    private String structureType(String block) {
        String first = block.lines().findFirst().orElse("").strip();
        if (first.startsWith("#")) return "heading";
        if (block.contains("<table") || (block.contains("|") && block.lines().count() > 1)) return "table";
        if (block.contains("$$") || block.contains("\\begin{") || block.contains("\\frac")) return "formula";
        if (first.matches("^(第?[0-9一二三四五六七八九十]+[题、.]|[（(]?[A-D0-9]+[）).、]).*")) return "question";
        return "paragraph";
    }

    private String tail(String value, int chars) {
        if (value.length() <= chars) return value;
        return value.substring(value.length() - chars);
    }

    private record ChunkPiece(String text, String type) {}

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private record ParseRequestRecord(String filePath, boolean forceReParse) {}
}
