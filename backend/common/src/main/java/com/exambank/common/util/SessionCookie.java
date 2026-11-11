package com.exambank.common.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

public final class SessionCookie {
    public static final String STUDENT = "EXAM_STUDENT_SESSION";
    public static final String ADMIN = "EXAM_ADMIN_SESSION";
    public static final String CSRF = "EXAM_CSRF";

    private SessionCookie() {}

    public static void add(HttpServletResponse response, HttpServletRequest request, String name, String token) {
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))
                || "true".equalsIgnoreCase(System.getenv("COOKIE_SECURE"));
        ResponseCookie cookie = ResponseCookie.from(name, token)
                .httpOnly(true).secure(secure).sameSite("Lax").path("/")
                .maxAge(Duration.ofMinutes(30)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        String csrf = TokenGenerator.generate();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(CSRF, csrf)
                .httpOnly(false).secure(secure).sameSite("Lax").path("/")
                .maxAge(Duration.ofMinutes(30)).build().toString());
    }

    public static void clear(HttpServletResponse response, String name) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, "")
                .httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(CSRF, "")
                .httpOnly(false).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
    }
}
