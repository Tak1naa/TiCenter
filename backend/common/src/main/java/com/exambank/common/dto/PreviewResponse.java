package com.exambank.common.dto;

public class PreviewResponse {
    private int pageNumber;
    private String text;
    private String imageUrl;
    private String parseMethod;
    private Double confidence;
    private String fileName;
    private int totalPages;

    public PreviewResponse() {}

    public PreviewResponse(int pageNumber, String text, String imageUrl, String parseMethod,
                           Double confidence, String fileName, int totalPages) {
        this.pageNumber = pageNumber;
        this.text = text;
        this.imageUrl = imageUrl;
        this.parseMethod = parseMethod;
        this.confidence = confidence;
        this.fileName = fileName;
        this.totalPages = totalPages;
    }

    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getParseMethod() { return parseMethod; }
    public void setParseMethod(String parseMethod) { this.parseMethod = parseMethod; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
}
