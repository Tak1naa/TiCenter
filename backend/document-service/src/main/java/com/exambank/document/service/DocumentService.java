package com.exambank.document.service;

import com.exambank.common.dto.*;
import com.exambank.common.entity.ExamFile;
import com.exambank.common.entity.TextChunk;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.common.repository.TextChunkRepository;
import com.exambank.common.security.FileAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final ExamFileRepository examFileRepository;
    private final TextChunkRepository textChunkRepository;
    private final ClamAvScanner clamAvScanner;
    private final FileAccessPolicy fileAccessPolicy;
    @Value("${app.ocr.service-url:http://localhost:8001}")
    private String ocrServiceUrl;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();

    @Value("${app.exam-bank.root-path:/home/takina/AiQuestionBank/data/exam-bank}")
    private String rootPath;

    public DocumentService(ExamFileRepository examFileRepository,
                           TextChunkRepository textChunkRepository,
                           ClamAvScanner clamAvScanner,
                           FileAccessPolicy fileAccessPolicy) {
        this.examFileRepository = examFileRepository;
        this.textChunkRepository = textChunkRepository;
        this.clamAvScanner = clamAvScanner;
        this.fileAccessPolicy = fileAccessPolicy;
    }

    public StatusResponse getStatus(String path) {
        List<ExamFile> files;
        if (path != null && !path.isEmpty()) {
            files = examFileRepository.findByFilePathStartingWith(path);
        } else {
            files = examFileRepository.findAll();
        }

        int total = files.size();
        int completed = 0, processing = 0, failed = 0, pending = 0;
        for (ExamFile f : files) {
            switch (f.getParseStatus()) {
                case "completed" -> completed++;
                case "processing" -> processing++;
                case "failed" -> failed++;
                default -> pending++;
            }
        }

        List<FileStatus> fileStatuses = files.stream().map(f ->
                new FileStatus(f.getFilePath(), f.getParseStatus(), f.getParseMethod(),
                        f.getTotalPages(), f.getParsedPages(),
                        f.getParsedAt() != null ? f.getParsedAt().toString() : null,
                        f.getErrorMessage(), f.getFileSize(), f.getFileType()))
                .collect(Collectors.toList());

        return new StatusResponse(fileStatuses,
                new StatusSummary(total, completed, processing, failed, pending));
    }

    @Cacheable(value = "file-tree", key = "(#privileged ? 'priv:' : 'public:') + (#path == null ? '' : #path)")
    public List<FileTreeNode> getFileTree(String path, boolean privileged) {
        Path basePath = path != null && !path.isEmpty()
                ? Paths.get(path)
                : Paths.get(rootPath);

        if (!Files.exists(basePath)) return List.of();

        List<FileTreeNode> result = new ArrayList<>();
        try (var stream = Files.list(basePath)) {
            List<Path> entries = stream.sorted().toList();
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    List<FileTreeNode> children = getFileTree(entry.toAbsolutePath().toString(), privileged);
                    if (!children.isEmpty()) result.add(new FileTreeNode(
                            entry.getFileName().toString(),
                            entry.toAbsolutePath().toString(),
                            "directory", null, null, null, null, null, children));
                } else if (isSupportedFile(entry)) {
                    String absPath = entry.toAbsolutePath().toString();
                    ExamFile ef = examFileRepository.findByFilePath(absPath).orElse(null);
                    if (ef != null && (privileged || FileAccessPolicy.PUBLIC.equals(ef.getAccessScope()))) {
                        result.add(new FileTreeNode(
                                entry.getFileName().toString(), absPath, "file",
                                ef.getParseStatus(), ef.getId(), ef.getCourseId(), ef.getDocumentVersion(), ef.getActiveGenerationId(),
                                List.of()));  // empty list, not null
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error listing directory: {}", basePath, e);
        }
        return result;
    }

    public List<FileTreeNode> getFileTree(String path) {
        return getFileTree(path, fileAccessPolicy.isPrivileged());
    }

    public PreviewResponse getPreview(Long fileId, String filePath, int page) {
        ExamFile file = resolveReadableFile(fileId, filePath);
        Path requestedPath = Paths.get(file.getFilePath()).normalize().toAbsolutePath();
        Path rootAbsolute = Paths.get(rootPath).normalize().toAbsolutePath();
        if (!requestedPath.startsWith(rootAbsolute)) {
            throw new SecurityException("拒绝访问：路径不在题库目录范围内");
        }

        List<TextChunk> chunks = file.getActiveGenerationId() == null
                ? textChunkRepository.findByFileIdOrderByChunkIndex(file.getId())
                : textChunkRepository.findByFileIdAndGenerationIdOrderByChunkIndex(
                        file.getId(), file.getActiveGenerationId());

        String pageText = chunks.stream()
                .filter(c -> c.getPageNumber() != null && c.getPageNumber() == page)
                .map(TextChunk::getContent)
                .collect(Collectors.joining("\n\n"));

        String imageUrl = null;
        if ("ocr".equals(file.getParseMethod())) {
            String fileName = file.getFileName();
            String stem = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            imageUrl = "/cache/ocr/" + stem + "_p" + page + ".png";
        }

        return new PreviewResponse(page, pageText, imageUrl, file.getParseMethod(),
                null, file.getFileName(),
                file.getTotalPages() != null ? file.getTotalPages() : 0);
    }

    public java.util.Map<String, Object> workspace(Long fileId) {
        ExamFile file = fileAccessPolicy.readableById(fileId).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("fileId", file.getId()); data.put("fileName", file.getFileName());
        data.put("generationId", file.getActiveGenerationId()); data.put("documentVersion", file.getDocumentVersion());
        data.put("courseId", file.getCourseId()); data.put("status", file.getParseStatus());
        data.put("totalPages", file.getTotalPages()); data.put("locationPrecision", "page");
        return data;
    }

    public void requireGeneration(Long fileId, String generationId) {
        if (generationId == null || generationId.isBlank()) return;
        ExamFile file = resolveReadableFile(fileId, null);
        if (!Objects.equals(generationId, file.getActiveGenerationId())) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "CONTEXT_VERSION_CHANGED");
    }

    public byte[] pageImage(Long fileId, String generationId, int page) {
        ExamFile file = resolveReadableFile(fileId, null);
        requireGeneration(fileId, generationId);
        if (page < 1 || (file.getTotalPages() != null && page > file.getTotalPages()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        try {
            String path = java.net.URLEncoder.encode(file.getFilePath(), java.nio.charset.StandardCharsets.UTF_8);
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(ocrServiceUrl + "/render-page?filePath=" + path + "&page=" + page))
                    .timeout(java.time.Duration.ofSeconds(90))
                    .header("X-Api-Token", Objects.toString(System.getenv("API_SERVICE_TOKEN"), "")).GET().build();
            var response = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 20 * 1024 * 1024)
                throw new IOException("page render unavailable");
            requireGeneration(fileId, generationId);
            return response.body();
        } catch (org.springframework.web.server.ResponseStatusException e) { throw e; }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "页面转换暂不可用");
        }
    }

    public PreviewResponse getPreview(String filePath, int page) {
        return getPreview(null, filePath, page);
    }

    /** 打开文件原始流（前端渲染用）。路径校验防目录穿越。
     *  流式返回：100MB 文件不整读进内存（原来 readAllBytes 每请求吃满堆）。 */
    public java.io.InputStream getRawStream(Long fileId, String filePath) throws Exception {
        ExamFile file = resolveReadableFile(fileId, filePath);
        Path requestedPath = Paths.get(file.getFilePath()).normalize().toAbsolutePath();
        Path rootAbsolute = Paths.get(rootPath).normalize().toAbsolutePath();
        if (!requestedPath.startsWith(rootAbsolute)) {
            throw new SecurityException("拒绝访问：路径不在题库目录范围内");
        }
        return Files.newInputStream(requestedPath);
    }

    public java.io.InputStream getRawStream(String filePath) throws Exception {
        return getRawStream(null, filePath);
    }

    public String getReadableFileName(Long fileId, String filePath) {
        return resolveReadableFile(fileId, filePath).getFileName();
    }

    private ExamFile resolveReadableFile(Long fileId, String filePath) {
        if (fileId != null && filePath != null && !filePath.isBlank()) throw new SecurityException("ambiguous file selector");
        ExamFile file = fileId != null
                ? fileAccessPolicy.readableById(fileId).orElseThrow(() -> new SecurityException("file not found"))
                : fileAccessPolicy.readableByPath(normalizePath(filePath)).orElseThrow(() -> new SecurityException("file not found"));
        return file;
    }

    private String normalizePath(String filePath) {
        if (filePath == null || filePath.isBlank()) throw new SecurityException("file not found");
        Path requested = Paths.get(filePath).normalize().toAbsolutePath();
        Path root = Paths.get(rootPath).normalize().toAbsolutePath();
        if (!requested.startsWith(root)) throw new SecurityException("file not found");
        return requested.toString();
    }

    private boolean isSupportedFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".doc")
                || name.endsWith(".pptx") || name.endsWith(".ppt")
                || name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".bmp")
                || name.endsWith(".tiff");
    }

    /** 与 FileScanner.SUPPORTED_EXTENSIONS 保持一致 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc", ".pptx", ".ppt", ".txt", ".md",
            ".png", ".jpg", ".jpeg", ".bmp", ".tiff"
    );

    private static final Set<String> BLOCKED_MIME_TYPES = Set.of(
            "application/x-msdownload", "application/x-executable",
            "application/x-sh", "text/html", "application/x-httpd-php",
            "application/x-java-archive", "application/x-shockwave-flash"
    );

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    /**
     * Saves an uploaded file to the exam bank root directory after security checks.
     */
    @CacheEvict(value = "file-tree", allEntries = true)
    public String saveUploadedFile(MultipartFile file) throws IOException {
        // Validate file
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("文件名为空");
        }

        // Check extension
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) ext = originalName.substring(dot).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext);
        }

        // Check MIME type
        String contentType = file.getContentType();
        if (contentType != null && BLOCKED_MIME_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("不允许的文件类型: " + contentType);
        }

        // Check size
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件过大（最大100MB）");
        }

        // Sanitize filename — remove path traversal
        String safeName = originalName.replaceAll("[<>:\"/\\\\|?*\0]", "_")
                .replaceAll("\\.{2,}", ".");
        if (safeName.startsWith(".")) safeName = "_" + safeName;

        // Content moderation: 仅对纯文本文件扫描内容，Office/PDF 靠解析层防护
        if (ext.matches("\\.(txt|md)$")) {
            byte[] preview;
            try (InputStream input = file.getInputStream()) { preview = input.readNBytes(4096); }
            String contentPreview = new String(preview, java.nio.charset.StandardCharsets.UTF_8);
            if (containsBlockedContent(contentPreview)) {
                throw new IllegalArgumentException("文件内容包含禁止的代码或不当内容");
            }
        }

        // Quarantine first; validate magic bytes, archive expansion and malware before publishing.
        Path root = Paths.get(rootPath).normalize().toAbsolutePath();
        Files.createDirectories(root);
        Path quarantine = Files.createTempFile(root, ".upload-", ".quarantine");
        try (InputStream is = file.getInputStream()) {
            Files.copy(is, quarantine, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            validateDetectedType(quarantine, ext);
            if (Set.of(".docx", ".pptx").contains(ext)) validateOfficeArchive(quarantine);
            clamAvScanner.assertClean(quarantine);
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(quarantine);
            throw e;
        }

        // Publish to exam bank root
        Path destPath = Paths.get(rootPath, safeName);
        // Avoid overwrite: append number if exists
        if (Files.exists(destPath)) {
            String base = safeName.substring(0, safeName.lastIndexOf('.'));
            int counter = 1;
            while (Files.exists(Paths.get(rootPath, base + "_" + counter + ext))) {
                counter++;
            }
            destPath = Paths.get(rootPath, base + "_" + counter + ext);
        }

        Files.move(quarantine, destPath, StandardCopyOption.ATOMIC_MOVE);

        log.info("Uploaded file: {} -> {}", originalName, destPath);
        return destPath.toAbsolutePath().toString();
    }

    private void validateDetectedType(Path path, String ext) throws IOException {
        String detected = new org.apache.tika.Tika().detect(path);
        Map<String, Set<String>> allowed = Map.ofEntries(
                Map.entry(".pdf", Set.of("application/pdf")),
                Map.entry(".doc", Set.of("application/msword", "application/x-tika-msoffice")),
                Map.entry(".docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/zip")),
                Map.entry(".ppt", Set.of("application/vnd.ms-powerpoint", "application/x-tika-msoffice")),
                Map.entry(".pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/zip")),
                Map.entry(".txt", Set.of("text/plain")), Map.entry(".md", Set.of("text/plain")),
                Map.entry(".png", Set.of("image/png")), Map.entry(".jpg", Set.of("image/jpeg")),
                Map.entry(".jpeg", Set.of("image/jpeg")), Map.entry(".bmp", Set.of("image/bmp")),
                Map.entry(".tiff", Set.of("image/tiff")));
        if (!allowed.getOrDefault(ext, Set.of()).contains(detected)) {
            throw new IllegalArgumentException("文件内容与扩展名不匹配");
        }
    }

    private void validateOfficeArchive(Path path) throws IOException {
        long expanded = 0;
        int entries = 0;
        try (var zip = new java.util.zip.ZipInputStream(Files.newInputStream(path))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10_000) throw new IllegalArgumentException("Office 文件条目过多");
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    expanded += read;
                    if (expanded > 500L * 1024 * 1024) {
                        throw new IllegalArgumentException("Office 文件解压后过大");
                    }
                }
            }
        }
    }

    private boolean containsBlockedContent(String content) {
        String lower = content.toLowerCase();
        // XSS patterns
        if (lower.contains("<script") || lower.contains("<iframe")
                || lower.contains("javascript:") || lower.contains("onerror=")
                || lower.contains("onload=")) {
            return true;
        }
        // Sensitive words
        for (String word : new String[]{"色情", "赌博", "毒品", "枪支"}) {
            if (lower.contains(word)) return true;
        }
        return false;
    }
}
