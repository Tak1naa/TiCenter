package com.exambank.chat.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/** Token-aware prompt budgeting backed by the open-source JTokkit tokenizer. */
public final class PromptBudgeter {
    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private PromptBudgeter() {}

    public static int count(String value) {
        return value == null || value.isEmpty() ? 0 : ENCODING.countTokens(value);
    }

    public static String truncate(String value, int maxTokens) {
        if (value == null || value.isEmpty() || maxTokens <= 0) return "";
        if (count(value) <= maxTokens) return value;
        int low = 0, high = value.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (count(value.substring(0, middle)) <= maxTokens) low = middle;
            else high = middle - 1;
        }
        return value.substring(0, low).stripTrailing();
    }
}
