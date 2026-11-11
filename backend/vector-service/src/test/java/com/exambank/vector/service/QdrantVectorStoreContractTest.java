package com.exambank.vector.service;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Contract against the pinned real Qdrant REST API, not an in-memory vector fake. */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QdrantVectorStoreContractTest {
    private static final String KEY = "contract-test-key";

    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.18.2"))
            .withEnv("QDRANT__SERVICE__API_KEY", KEY)
            .withExposedPorts(6333);

    private QdrantVectorStore store;

    @BeforeEach
    void setUp() {
        store = new QdrantVectorStore("http://127.0.0.1:" + QDRANT.getMappedPort(6333),
                "contract_chunks", KEY, 4);
    }

    @Test
    @Order(1)
    void createsCollectionIdempotentlyAndPerformsFilteredHybridSearch() {
        store.ensureCollection();
        store.ensureCollection();
        store.upsert(List.of(
                point(101, 1, "g1", true, "PUBLIC", "math", List.of(1d, 0d, 0d, 0d), 10),
                point(102, 1, "g0", false, "PUBLIC", "math", List.of(.9d, .1d, 0d, 0d), 10),
                point(103, 2, "g2", true, "PRIVATE", "math", List.of(1d, 0d, 0d, 0d), 10),
                point(104, 3, "g3", true, "PUBLIC", "physics", List.of(1d, 0d, 0d, 0d), 10)));

        var result = store.search(List.of(1d, 0d, 0d, 0d),
                new QdrantVectorStore.SparseVector(List.of(10), List.of(1d)),
                List.of(1L), "math", 10);
        assertThat(result.mode()).isEqualTo("qdrant-hybrid");
        assertThat(result.hits()).extracting(QdrantVectorStore.SearchHit::chunkId).containsExactly(101L);
    }

    @Test
    @Order(2)
    void activatesCountsAndDeletesOneGenerationIdempotently() {
        store.ensureCollection();
        store.upsert(List.of(point(201, 5, "new", false, "PUBLIC", "math",
                List.of(0d, 1d, 0d, 0d), 20)));
        assertThat(store.countGeneration(5, "new")).isEqualTo(1);
        assertThat(store.verifyGenerationSample(5, "new")).isTrue();
        store.setGenerationActive(5, "new", true);
        assertThat(store.search(List.of(0d, 1d, 0d, 0d),
                new QdrantVectorStore.SparseVector(List.of(), List.of()),
                List.of(5L), null, 5).hits()).hasSize(1);
        store.deleteGeneration(5, "new");
        store.deleteGeneration(5, "new");
        assertThat(store.countGeneration(5, "new")).isZero();
    }

    @Test
    void rejectsWrongDenseDimensionBeforeQuery() {
        assertThatThrownBy(() -> store.search(List.of(1d, 2d),
                new QdrantVectorStore.SparseVector(List.of(), List.of()),
                List.of(), null, 5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expected 4");
    }

    @Test
    void rejectsWrongApiKey() {
        QdrantVectorStore wrong = new QdrantVectorStore(
                "http://127.0.0.1:" + QDRANT.getMappedPort(6333), "contract_chunks", "wrong", 4);
        assertThatThrownBy(wrong::ensureCollection).isInstanceOf(Exception.class);
    }

    @Test
    void returnsEmptyInsteadOfBroadeningAnUnknownCourse() {
        store.ensureCollection();
        var result = store.search(List.of(1d, 0d, 0d, 0d),
                new QdrantVectorStore.SparseVector(List.of(10), List.of(1d)),
                List.of(), "course-does-not-exist", 10);
        assertThat(result.hits()).isEmpty();
    }

    @Test
    @Order(99)
    void failsWhileQdrantIsStoppedAndRecoversAfterRestart() {
        store.ensureCollection();
        int stoppedPort = QDRANT.getMappedPort(6333);
        QDRANT.stop();
        QdrantVectorStore unavailable = new QdrantVectorStore(
                "http://127.0.0.1:" + stoppedPort, "contract_chunks", KEY, 4);
        assertThatThrownBy(unavailable::ensureCollection).isInstanceOf(Exception.class);
        QDRANT.start();
        QdrantVectorStore recovered = new QdrantVectorStore(
                "http://127.0.0.1:" + QDRANT.getMappedPort(6333), "contract_chunks", KEY, 4);
        recovered.ensureCollection();
    }

    private QdrantVectorStore.PointData point(long id, long fileId, String generation, boolean active,
                                               String scope, String course, List<Double> dense, int sparseIndex) {
        return new QdrantVectorStore.PointData(id, dense,
                new QdrantVectorStore.SparseVector(List.of(sparseIndex), List.of(1d)),
                Map.of("content", "chunk-" + id, "fileId", fileId, "page", 1,
                        "chunkIndex", 0, "courseId", course, "accessScope", scope,
                        "documentVersion", "v1", "generationId", generation, "active", active));
    }
}
