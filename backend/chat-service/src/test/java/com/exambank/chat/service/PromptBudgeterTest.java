package com.exambank.chat.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PromptBudgeterTest {
    @Test
    void truncatesChineseTextToConfiguredTokenBudget() {
        String value = "计算机组成原理中的高速缓存一致性。".repeat(100);
        String truncated = PromptBudgeter.truncate(value, 80);
        assertThat(truncated).isNotEmpty();
        assertThat(PromptBudgeter.count(truncated)).isLessThanOrEqualTo(80);
        assertThat(truncated.length()).isLessThan(value.length());
    }
}
