package com.exambank.common.dto;

public record ResetPasswordRequest(String token, String newPassword) {}
