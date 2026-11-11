package com.exambank.common.dto;

import java.util.List;

public record ChatResponse(String answer, List<Reference> references, String sessionId) {}
