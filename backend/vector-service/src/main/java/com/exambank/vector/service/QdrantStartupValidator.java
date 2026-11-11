package com.exambank.vector.service;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Validates the configured versioned collection without ever deleting or rebuilding it. */
@Component
public class QdrantStartupValidator implements SmartInitializingSingleton {
    private final QdrantVectorStore store;
    private final boolean enabled;
    @Value("${app.retrieval.backend:llamaindex}")
    private String backend;

    public QdrantStartupValidator(QdrantVectorStore store,
                                  @Value("${app.qdrant.validate-on-startup:true}") boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (enabled && !"llamaindex".equals(backend)) store.ensureCollection();
    }
}
