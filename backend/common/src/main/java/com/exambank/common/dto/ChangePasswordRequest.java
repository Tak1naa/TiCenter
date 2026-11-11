package com.exambank.common.dto;

public record ChangePasswordRequest(String oldPassword, String newPassword) {}
