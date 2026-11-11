package com.exambank.vector.config;

import com.exambank.common.entity.TextChunk;
import com.exambank.common.repository.TextChunkRepository;
import com.exambank.vector.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时只增量索引 pending/stale 块，避免每次重启扫描和重嵌入全库。 */
@Component
public class StartupIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIndexer.class);

    private final TextChunkRepository textChunkRepository;
    private final VectorIndexService vectorIndexService;

    public StartupIndexer(TextChunkRepository textChunkRepository,
                          VectorIndexService vectorIndexService) {
        this.textChunkRepository = textChunkRepository;
        this.vectorIndexService = vectorIndexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        long pending = textChunkRepository.countByIndexStatus("pending");
        if (pending == 0) return;
        log.info("Startup indexer: {} pending chunks to index...", pending);
        int indexed = vectorIndexService.indexAll();
        log.info("Startup indexer done: {}/{} indexed", indexed, pending);
    }
}
