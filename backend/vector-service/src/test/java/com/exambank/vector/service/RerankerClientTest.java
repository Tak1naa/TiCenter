package com.exambank.vector.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RerankerClientTest {
    @Test
    void mapsRerankerIndicesBackToStableChunkIds() {
        RerankerClient client = new RerankerClient("", "", "test", false, 8);
        var hits = List.of(
                new QdrantVectorStore.SearchHit(41, "first", .03, Map.of("generationId", "g1")),
                new QdrantVectorStore.SearchHit(52, "second", .02, Map.of("generationId", "g1")));
        var result = client.mapScores(hits, List.of(
                Map.of("index", 1, "relevance_score", .95),
                Map.of("index", 0, "relevance_score", .70)));
        assertThat(result.applied()).isTrue();
        assertThat(result.hits()).extracting(QdrantVectorStore.SearchHit::chunkId)
                .containsExactly(52L, 41L);
        assertThat(result.hits().getFirst().payload()).containsEntry("rrfScore", .02)
                .containsEntry("rerankScore", .95);
    }
}
