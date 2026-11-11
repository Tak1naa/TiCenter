package com.exambank.common.dto;

public record StatusSummary(int total, int completed, int processing, int failed, int pending) {}
