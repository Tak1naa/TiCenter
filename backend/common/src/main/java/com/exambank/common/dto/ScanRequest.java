package com.exambank.common.dto;

public record ScanRequest(String path, boolean forceReParse) {
    public ScanRequest() { this(null, false); }
}
