package com.exambank.common.dto;

import java.util.List;

public class ParseResult {
    private String filePath;
    private String fileName;
    private String parseMethod;
    private List<PageResult> pages;
    private int totalPages;
    private String parseTime;
    private String fileHash;

    public ParseResult() {}

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getParseMethod() { return parseMethod; }
    public void setParseMethod(String parseMethod) { this.parseMethod = parseMethod; }
    public List<PageResult> getPages() { return pages; }
    public void setPages(List<PageResult> pages) { this.pages = pages; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public String getParseTime() { return parseTime; }
    public void setParseTime(String parseTime) { this.parseTime = parseTime; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public static class PageResult {
        private int pageNumber;
        private String text;
        private String method;
        private Double confidence;
        private String imagePath;

        public PageResult() {}

        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    }
}
