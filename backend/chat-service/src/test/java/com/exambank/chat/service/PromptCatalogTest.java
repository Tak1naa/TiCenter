package com.exambank.chat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptCatalogTest {
    @Test
    void promptPrefixIsVersionedCompactAndKeepsSafetyRules() {
        assertThat(PromptCatalog.VERSION).isNotBlank();
        assertThat(PromptCatalog.RAG_SYSTEM.length()).isLessThan(500);
        assertThat(PromptCatalog.RAG_SYSTEM).contains("[S1]", "不可信数据", "资料不足");
        assertThat(PromptCatalog.QUESTION_SYSTEM).contains("只输出 JSON", "source_page");
    }
}
