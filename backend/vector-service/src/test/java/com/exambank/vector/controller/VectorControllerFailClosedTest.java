package com.exambank.vector.controller;

import com.exambank.common.entity.ExamFile;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.common.repository.TextChunkRepository;
import com.exambank.vector.service.EmbeddingClient;
import com.exambank.vector.service.QdrantVectorStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorControllerFailClosedTest {
    @Test
    void mixedKnownAndUnknownSelectionDoesNotTouchEmbeddingOrQdrant() {
        ExamFile known = new ExamFile();
        known.setId(1L); known.setAccessScope("PUBLIC"); known.setCourseId("default");
        ExamFileRepository files = repository(known);
        CountingStore store = new CountingStore();
        CountingEmbedding embedding = new CountingEmbedding();
        VectorController controller = new VectorController(emptyChunks(), embedding, store, null, files, null);

        var response = controller.search(Map.of("query", "test", "selectionProvided", true,
                "fileIds", List.of(1, 999), "topK", 20));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "INVALID_FILE_SELECTION");
        assertThat(store.calls).isZero();
        assertThat(embedding.calls).isZero();
    }

    private ExamFileRepository repository(ExamFile known) {
        return (ExamFileRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ExamFileRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findAllById" -> List.of(known);
                    case "toString" -> "OneFileRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private TextChunkRepository emptyChunks() {
        return (TextChunkRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TextChunkRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "EmptyChunkRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    static class CountingStore extends QdrantVectorStore {
        int calls;
        CountingStore() { super("http://127.0.0.1:1", "never-called", "", 4); }
        @Override public void ensureCollection() { calls++; }
        @Override public SearchResult search(List<Double> dense, SparseVector sparse, List<Long> fileIds,
                                             String courseId, int topK) {
            calls++; return new SearchResult(List.of(), "test", false);
        }
    }

    static class CountingEmbedding extends EmbeddingClient {
        int calls;
        @Override public List<Double> embedQuery(String text) { calls++; return List.of(); }
        @Override public SparseVector embedSparseQuery(String text) {
            calls++; return new SparseVector(List.of(), List.of());
        }
    }
}
