package com.exambank.common.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content moderation filter — blocks requests containing XSS patterns or sensitive words.
 */
public class ContentModerationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ContentModerationFilter.class);

    private static final Set<String> PROTECTED_METHODS = Set.of("POST", "PUT", "PATCH");

    private static final Pattern[] XSS_PATTERNS = {
            Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<iframe\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<object\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<embed\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("on\\w+\\s*=\\s*[\"']?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE),
    };

    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "fuck", "shit", "色情", "赌博", "毒品", "枪支", "暴力恐怖"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String method = httpReq.getMethod().toUpperCase();
        if (!PROTECTED_METHODS.contains(method)) {
            chain.doFilter(request, response);
            return;
        }

        // Read body for text-based endpoints
        String contentType = httpReq.getContentType();
        if (contentType != null && contentType.contains("application/json")) {
            CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(httpReq);
            String body = new String(wrapper.getBody(), StandardCharsets.UTF_8);

            // Check XSS patterns
            for (Pattern pattern : XSS_PATTERNS) {
                if (pattern.matcher(body).find()) {
                    log.warn("XSS pattern detected in request to {}", httpReq.getRequestURI());
                    httpResp.setStatus(400);
                    httpResp.setContentType("application/json;charset=UTF-8");
                    httpResp.getWriter().write("{\"error\":\"检测到潜在的代码注入\"}");
                    return;
                }
            }

            // Check sensitive words
            String lower = body.toLowerCase();
            for (String word : SENSITIVE_WORDS) {
                if (lower.contains(word)) {
                    log.warn("Sensitive word detected in request to {}", httpReq.getRequestURI());
                    httpResp.setStatus(400);
                    httpResp.setContentType("application/json;charset=UTF-8");
                    httpResp.getWriter().write("{\"error\":\"输入包含不当内容\"}");
                    return;
                }
            }

            // Body length limit
            if (body.length() > 50000) {
                httpResp.setStatus(400);
                httpResp.setContentType("application/json;charset=UTF-8");
                httpResp.getWriter().write("{\"error\":\"请求体过大\"}");
                return;
            }

            chain.doFilter(wrapper, response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Reuses the same wrapper pattern as HashVerificationFilter.
     */
    static class CachedBodyRequestWrapper extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final byte[] body;

        public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            this.body = sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        public byte[] getBody() { return body; }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private int pos = 0;
                @Override public boolean isFinished() { return pos >= body.length; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
                @Override public int read() {
                    return pos < body.length ? body[pos++] & 0xFF : -1;
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
