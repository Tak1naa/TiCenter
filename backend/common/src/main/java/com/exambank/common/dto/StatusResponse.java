package com.exambank.common.dto;

import java.util.List;

public record StatusResponse(List<FileStatus> files, StatusSummary summary) {}
