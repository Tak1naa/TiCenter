package com.exambank.common.dto;

public class StudyResult {
    private String type;
    private String subject;
    private Object content;
    private String generatedAt;

    public StudyResult() {}

    public StudyResult(String type, String subject, Object content, String generatedAt) {
        this.type = type;
        this.subject = subject;
        this.content = content;
        this.generatedAt = generatedAt;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public Object getContent() { return content; }
    public void setContent(Object content) { this.content = content; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
}
