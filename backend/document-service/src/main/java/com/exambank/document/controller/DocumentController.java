package com.exambank.document.controller;

import com.exambank.common.dto.*;
import com.exambank.document.scanner.FileScanner;
import com.exambank.document.service.DocumentService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/document")
public class DocumentController {

    private final FileScanner fileScanner;
    private final DocumentService documentService;

    public DocumentController(FileScanner fileScanner, DocumentService documentService) {
        this.fileScanner = fileScanner;
        this.documentService = documentService;
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','EDITOR','SERVICE')")
    public ResponseEntity<ScanResponse> scan(@RequestBody(required = false) ScanRequest request) {
        String path = request != null ? request.path() : null;
        boolean force = request != null && request.forceReParse();
        String taskId = fileScanner.scanAsync(path, force);
        return ResponseEntity.ok(new ScanResponse(taskId, "started", 0));
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','EDITOR','VIEWER','SERVICE')")
    public ResponseEntity<StatusResponse> status(
            @RequestParam(value = "path", defaultValue = "") String path) {
        return ResponseEntity.ok(documentService.getStatus(path));
    }

    @GetMapping("/tree")
    public ResponseEntity<List<FileTreeNode>> tree(
            @RequestParam(value = "path", defaultValue = "") String path) {
        return ResponseEntity.ok(documentService.getFileTree(path));
    }

    @GetMapping("/{fileId}/workspace")
    public Map<String, Object> workspace(@PathVariable Long fileId) {
        return documentService.workspace(fileId);
    }

    @GetMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestParam(value = "path", required = false) String filePath,
            @RequestParam(value = "generationId", required = false) String generationId,
            @RequestParam(value = "page", defaultValue = "1") int page) {
        try {
            documentService.requireGeneration(fileId, generationId);
            return ResponseEntity.ok(documentService.getPreview(fileId, filePath, page));
        } catch (SecurityException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 返回文件原始流（前端用 pdfjs/docx-preview 等直接渲染）。路径校验防目录穿越。 */
    @GetMapping("/raw")
    public ResponseEntity<Resource> raw(@RequestParam(value = "fileId", required = false) Long fileId,
                                        @RequestParam(value = "path", required = false) String filePath,
                                        @RequestParam(value = "generationId", required = false) String generationId) {
        try {
            documentService.requireGeneration(fileId, generationId);
            java.io.InputStream stream = documentService.getRawStream(fileId, filePath);
            String resolvedName = documentService.getReadableFileName(fileId, filePath);
            String contentType = contentTypeFor(resolvedName == null ? "" : resolvedName);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(new org.springframework.core.io.InputStreamResource(stream));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        } catch (SecurityException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private String contentTypeFor(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".tiff")) return "image/tiff";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    @GetMapping(value = "/page-image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> pageImage(@RequestParam Long fileId,
            @RequestParam(required = false) String generationId,
            @RequestParam(defaultValue = "1") int page) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(documentService.pageImage(fileId, generationId, page));
    }

    @PostMapping("/reparse")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','EDITOR','SERVICE')")
    public ResponseEntity<ScanResponse> reparse(@RequestBody ScanRequest request) {
        String taskId = fileScanner.scanAsync(request.path(), true);
        return ResponseEntity.ok(new ScanResponse(taskId, "started", 0));
    }

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','EDITOR','SERVICE')")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            String savedPath = documentService.saveUploadedFile(file);
            // Trigger re-scan of the parent directory
            String parentDir = savedPath.substring(0, savedPath.lastIndexOf('/'));
            fileScanner.scanAsync(parentDir, false);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "fileName", file.getOriginalFilename(),
                    "path", savedPath,
                    "message", "文件已上传，正在解析"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "上传参数错误"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "上传失败，请稍后重试"));
        }
    }
}
