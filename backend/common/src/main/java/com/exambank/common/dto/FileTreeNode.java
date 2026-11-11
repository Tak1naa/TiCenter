package com.exambank.common.dto;

import java.util.List;

public record FileTreeNode(String name, String path, String type, String status,
                           Long fileId, String courseId, String documentVersion, String generationId,
                           List<FileTreeNode> children) {}
