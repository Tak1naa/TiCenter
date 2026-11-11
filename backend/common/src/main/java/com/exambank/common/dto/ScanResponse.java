package com.exambank.common.dto;

public record ScanResponse(String taskId, String status, int filesFound) {
    public ScanResponse() { this(null, null, 0); }
}
