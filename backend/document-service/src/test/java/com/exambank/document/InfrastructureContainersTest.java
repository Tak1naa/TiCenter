package com.exambank.document;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Real dependency smoke test; skipped automatically on developer hosts without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class InfrastructureContainersTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("exam_bank");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);
    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.18.2")
            .withExposedPorts(6333);

    @Test
    void dependenciesAreReachable() {
        assertThat(MYSQL.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();
        assertThat(QDRANT.isRunning()).isTrue();
    }
}
