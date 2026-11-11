package com.exambank.common.dto;

public class Reference {
    private String fileName;
    private int page;
    private String snippet;
    private String filePath;
    private String sourceId;
    private String documentVersion;
    private Long chunkId;
    private Long fileId;
    private String generationId;
    private String accessScope;
    private String contextKind;
    private String locationPrecision;
    private java.util.List<Double> bbox;

    public Reference() {}

    public Reference(String fileName, int page, String snippet) {
        this.fileName = fileName;
        this.page = page;
        this.snippet = snippet;
    }

    public Reference(String fileName, int page, String snippet, String filePath) {
        this.fileName = fileName;
        this.page = page;
        this.snippet = snippet;
        this.filePath = filePath;
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getGenerationId() { return generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
    public String getAccessScope() { return accessScope; }
    public void setAccessScope(String accessScope) { this.accessScope = accessScope; }
    public String getContextKind() { return contextKind; }
    public void setContextKind(String value) { contextKind = value; }
    public String getLocationPrecision() { return locationPrecision; }
    public void setLocationPrecision(String value) { locationPrecision = value; }
    public java.util.List<Double> getBbox() { return bbox; }
    public void setBbox(java.util.List<Double> value) { bbox = value; }
}
