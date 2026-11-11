package com.exambank.common.dto;

import java.time.LocalDateTime;

public class FileStatus {
    private String path;
    private String status;
    private String parseMethod;
    private Integer totalPages;
    private Integer parsedPages;
    private String parseTime;
    private String errorMessage;
    private Long fileSize;
    private String fileType;

    public FileStatus() {}

    public FileStatus(String path, String status, String parseMethod, Integer totalPages,
                      Integer parsedPages, String parseTime, String errorMessage,
                      Long fileSize, String fileType) {
        this.path = path;
        this.status = status;
        this.parseMethod = parseMethod;
        this.totalPages = totalPages;
        this.parsedPages = parsedPages;
        this.parseTime = parseTime;
        this.errorMessage = errorMessage;
        this.fileSize = fileSize;
        this.fileType = fileType;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getParseMethod() { return parseMethod; }
    public void setParseMethod(String parseMethod) { this.parseMethod = parseMethod; }
    public Integer getTotalPages() { return totalPages; }
    public void setTotalPages(Integer totalPages) { this.totalPages = totalPages; }
    public Integer getParsedPages() { return parsedPages; }
    public void setParsedPages(Integer parsedPages) { this.parsedPages = parsedPages; }
    public String getParseTime() { return parseTime; }
    public void setParseTime(String parseTime) { this.parseTime = parseTime; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
}
