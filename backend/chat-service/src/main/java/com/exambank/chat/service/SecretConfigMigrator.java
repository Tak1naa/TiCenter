package com.exambank.chat.service;

import com.exambank.common.repository.SystemConfigRepository;
import com.exambank.common.security.SecretValueCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Encrypts legacy plaintext SMTP credentials as soon as an encryption key is configured. */
@Component
public class SecretConfigMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SecretConfigMigrator.class);
    private static final Set<String> SECRET_KEYS = Set.of("smtp_pass", "student_smtp_pass");

    private final SystemConfigRepository repository;
    private final SecretValueCrypto crypto;

    public SecretConfigMigrator(SystemConfigRepository repository, SecretValueCrypto crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @Override
    public void run(ApplicationArguments args) {
        int migrated = 0;
        for (String key : SECRET_KEYS) {
            var config = repository.findByConfigKey(key).orElse(null);
            if (config == null || config.getConfigValue() == null || config.getConfigValue().isBlank()
                    || crypto.isEncrypted(config.getConfigValue())) continue;
            if (!crypto.isConfigured()) {
                log.error("Secret config {} is plaintext and disabled until CONFIG_ENCRYPTION_KEY is configured", key);
                continue;
            }
            config.setConfigValue(crypto.encrypt(config.getConfigValue()));
            repository.save(config);
            migrated++;
        }
        if (migrated > 0) log.info("Encrypted {} legacy secret configuration value(s)", migrated);
    }
}
