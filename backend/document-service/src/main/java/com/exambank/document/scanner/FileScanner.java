package com.exambank.document.scanner;

import com.exambank.common.entity.ExamFile;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.document.service.ParseTaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Component
public class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    private final ExamFileRepository examFileRepository;
    private final ParseTaskScheduler parseTaskScheduler;

    @Value("${app.exam-bank.root-path:/home/takina/AiQuestionBank/data/exam-bank}")
    private String rootPath;

    @jakarta.annotation.PostConstruct
    public void init() {
        // rootPath injected via @Value
    }

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc", ".pptx", ".ppt", ".txt", ".md",
            ".png", ".jpg", ".jpeg", ".bmp", ".tiff"
    );

    private final Map<String, String> scanTasks = new java.util.concurrent.ConcurrentHashMap<>();

    public FileScanner(ExamFileRepository examFileRepository,
                       ParseTaskScheduler parseTaskScheduler) {
        this.examFileRepository = examFileRepository;
        this.parseTaskScheduler = parseTaskScheduler;
    }

    public String scanAsync(String targetPath, boolean forceReParse) {
        String taskId = "scan_" + UUID.randomUUID().toString().substring(0, 8);
        scanTasks.put(taskId, "running");
        cleanupScanTasks();
        // 路径约束：targetPath 必须在题库根目录内（防任意路径扫描 → 服务器文件泄露）
        String scanPath = targetPath != null ? targetPath : rootPath;
        try {
            java.nio.file.Path requested = java.nio.file.Paths.get(scanPath).normalize().toAbsolutePath();
            java.nio.file.Path root = java.nio.file.Paths.get(rootPath).normalize().toAbsolutePath();
            if (!requested.startsWith(root)) {
                scanTasks.put(taskId, "failed:路径不在题库目录范围内");
                return taskId;
            }
        } catch (Exception e) {
            scanTasks.put(taskId, "failed:路径无效");
            return taskId;
        }

        Thread.ofVirtual().start(() -> {
            try {
                int count = scan(scanPath, forceReParse);
                scanTasks.put(taskId, "completed:" + count);
                log.info("Scan task {} completed, {} files found", taskId, count);
            } catch (Exception e) {
                scanTasks.put(taskId, "failed:" + e.getMessage());
                log.error("Scan task {} failed", taskId, e);
            }
        });

        return taskId;
    }

    public int scan(String scanPath, boolean forceReParse) {
        Path root = Paths.get(scanPath);
        if (!Files.exists(root)) {
            System.out.println("[FileScanner] Path does not exist: " + scanPath);
            log.warn("Scan path does not exist: {}", scanPath);
            return 0;
        }

        List<Path> files = discoverFiles(root);
        int count = 0;

        for (Path file : files) {
            try {
                String filePath = file.toAbsolutePath().toString();
                String fileHash = computeFileHash(file);

                if (!forceReParse) {
                    Optional<ExamFile> existing = examFileRepository.findByFilePath(filePath);
                    if (existing.isPresent()
                            && fileHash.equals(existing.get().getFileHash())
                            && "completed".equals(existing.get().getParseStatus())) {
                        continue;
                    }
                }

                ExamFile examFile = examFileRepository.findByFilePath(filePath)
                        .orElseGet(() -> {
                            ExamFile ef = new ExamFile();
                            ef.setFilePath(filePath);
                            return ef;
                        });

                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                String fileName = file.getFileName().toString();
                String ext = fileName.contains(".")
                        ? fileName.substring(fileName.lastIndexOf('.')).toLowerCase()
                        : "";

                examFile.setFileName(fileName);
                examFile.setFileType(ext.replace(".", ""));
                examFile.setFileSize(attrs.size());
                examFile.setFileHash(fileHash);
                examFile.setLastModified(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()),
                        ZoneId.systemDefault()));
                examFile.setParseStatus("pending");

                examFileRepository.save(examFile);
                parseTaskScheduler.submit(examFile);
                count++;

            } catch (Exception e) {
                log.error("Error scanning file: {}", file, e);
            }
        }

        return count;
    }

    public List<Path> discoverFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase();
                    for (String ext : SUPPORTED_EXTENSIONS) {
                        if (name.endsWith(ext)) {
                            files.add(file);
                            break;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Error walking directory: {}", root, e);
        }
        return files;
    }

    private String computeFileHash(Path file) {
        // 流式哈希：100MB 文件不整读进内存（原来 readAllBytes 会吃满堆）
        try (java.io.InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public String getScanTaskStatus(String taskId) {
        return scanTasks.getOrDefault(taskId, "not_found");
    }

    /** 扫描任务 Map 只增不清理会泄漏：超过 500 条时清掉已结束的任务。 */
    private void cleanupScanTasks() {
        if (scanTasks.size() <= 500) return;
        scanTasks.entrySet().removeIf(e -> !"running".equals(e.getValue()));
        if (scanTasks.size() > 1000) scanTasks.clear();
    }
}
