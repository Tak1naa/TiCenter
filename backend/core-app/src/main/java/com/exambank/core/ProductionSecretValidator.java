package com.exambank.core;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import com.exambank.common.security.SecretValueCrypto;

/** Fail closed before serving traffic when production credentials are absent or obvious placeholders. */
@Component
public class ProductionSecretValidator implements ApplicationRunner {
    private final Environment environment;
    public ProductionSecretValidator(Environment environment) { this.environment = environment; }

    @Override public void run(ApplicationArguments args) {
        require("API_SERVICE_TOKEN", System.getenv("API_SERVICE_TOKEN"), 32);
        require("ADMIN_SEED_PASSWORD", System.getenv("ADMIN_SEED_PASSWORD"), 12);
        require("spring.datasource.password", environment.getProperty("spring.datasource.password"), 12);
        require("spring.data.redis.password", environment.getProperty("spring.data.redis.password"), 12);
        if (!SecretValueCrypto.isValidKey(System.getenv("CONFIG_ENCRYPTION_KEY"))) {
            throw new IllegalStateException("CONFIG_ENCRYPTION_KEY 必须是 32 字节 Base64 或 64 位十六进制密钥");
        }
    }

    private void require(String name, String value, int minimumLength) {
        String normalized = value == null ? "" : value.strip().toLowerCase();
        if (normalized.length() < minimumLength || List.of("change-me", "password", "123456", "admin").stream()
                .anyMatch(normalized::contains)) {
            throw new IllegalStateException(name + " 未配置、长度不足或仍为示例值");
        }
    }
}
