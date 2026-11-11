package com.exambank.document;

import com.exambank.common.dto.*;
import com.exambank.common.entity.ExamFile;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.document.scanner.FileScanner;
import com.exambank.document.service.DocumentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import com.exambank.common.config.SessionStore;

/**
 * Integration test for document-service.
 * Run: mvn -pl document-service test -Dspring.profiles.active=dev
 * Requires: nothing (H2 in-memory DB)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentServiceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private FileScanner fileScanner;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ExamFileRepository examFileRepository;

    private String baseUrl;
    private HttpEntity<Void> authenticated;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Student-Token", SessionStore.create("STUDENT", "test-student", "127.0.0.1"));
        authenticated = new HttpEntity<>(headers);
    }

    @Test
    @Order(1)
    @DisplayName("Context loads with isolated H2 profile")
    void contextLoads() {
        assertThat(fileScanner).isNotNull();
        assertThat(documentService).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("File scanner discovers test files")
    void fileScannerDiscoversFiles() {
        List<java.nio.file.Path> files = fileScanner.discoverFiles(
                java.nio.file.Paths.get("/home/takina/AiQuestionBank/data/exam-bank"));
        assertThat(files).isNotEmpty();
        assertThat(files.stream().anyMatch(f -> f.toString().contains(".txt"))).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("Scanner creates a bounded asynchronous task")
    void scanTriggersTask() {
        assertThat(fileScanner.scanAsync(null, false)).startsWith("scan_");
    }

    @Test
    @Order(4)
    @DisplayName("GET /api/document/tree returns directory structure")
    void treeReturnsDirectoryStructure() {
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/api/document/tree", HttpMethod.GET, authenticated, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        // Should return a JSON array for the directory listing
        assertThat(resp.getBody().trim()).startsWith("[");
    }

    @Test
    @Order(5)
    @DisplayName("GET /api/document/status returns summary")
    void statusReturnsSummary() {
        ResponseEntity<StatusResponse> denied = restTemplate.exchange(
                baseUrl + "/api/document/status", HttpMethod.GET, authenticated, StatusResponse.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Auth-Token", SessionStore.create("SERVICE", "document-test", "127.0.0.1"));
        ResponseEntity<StatusResponse> resp = restTemplate.exchange(
                baseUrl + "/api/document/status", HttpMethod.GET, new HttpEntity<>(headers), StatusResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().summary()).isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("Repository persists exam file metadata")
    void repositoryPersistsMetadata() {
        ExamFile file = new ExamFile();
        file.setFilePath("/tmp/test.pdf");
        file.setFileName("test.pdf");
        file.setParseStatus("pending");
        file.setCourseId("test-course");
        ExamFile saved = examFileRepository.save(file);
        assertThat(saved.getId()).isNotNull();
        assertThat(examFileRepository.findByFilePath("/tmp/test.pdf")).isPresent();
    }

    @Test
    @Order(7)
    @DisplayName("Only one worker can claim a file reparse")
    void parseClaimIsAtomicAndOwnerBound() {
        ExamFile file = new ExamFile();
        file.setFilePath("/tmp/claim-test.pdf");
        file.setFileName("claim-test.pdf");
        file.setParseStatus("pending");
        file = examFileRepository.saveAndFlush(file);
        var now = java.time.LocalDateTime.now();
        assertThat(examFileRepository.claimParse(file.getId(), "worker-1", now, now.minusMinutes(30))).isOne();
        assertThat(examFileRepository.claimParse(file.getId(), "worker-2", now, now.minusMinutes(30))).isZero();
        assertThat(examFileRepository.releaseParseClaim(file.getId(), "worker-2")).isZero();
        assertThat(examFileRepository.releaseParseClaim(file.getId(), "worker-1")).isOne();
    }
}
