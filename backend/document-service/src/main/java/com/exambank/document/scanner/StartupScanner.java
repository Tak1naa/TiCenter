package com.exambank.document.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "app.scan.enabled", havingValue = "true", matchIfMissing = true)
public class StartupScanner {

    private static final Logger log = LoggerFactory.getLogger(StartupScanner.class);

    private final FileScanner fileScanner;

    public StartupScanner(FileScanner fileScanner) {
        this.fileScanner = fileScanner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Starting initial exam bank scan...");
        try {
            fileScanner.scanAsync(null, false);
            log.info("Initial scan started");
        } catch (Exception e) {
            log.error("Initial scan failed", e);
        }
    }
}
