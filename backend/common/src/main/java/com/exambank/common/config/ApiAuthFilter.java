package com.exambank.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 统一鉴权过滤器：保护敏感端点（会话/个人数据/写操作），题库浏览类端点开放。
 * token 通过 X-Auth-Token / X-Student-Token / X-Admin-Token 传递，从 SessionStore 校验。
 */
public class ApiAuthFilter extends OncePerRequestFilter {

    /** 服务间共享 token（与 Python 服务一致，环境变量注入）。 */
    private static final String API_SERVICE_TOKEN = System.getenv("API_SERVICE_TOKEN") != null
            ? System.getenv("API_SERVICE_TOKEN") : "";

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/admin/auth/login",
            "/api/student/login",
            "/api/student/register",
            "/api/student/forgot-password",
            "/api/student/reset-password",
            "/health"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 非 /api、公开端点、或只读浏览端点放行
        if (!path.startsWith("/api") || isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 服务间调用（Java→Java / Java→Python 同款）：X-Api-Token 与共享 token 恒时比较
        String apiToken = request.getHeader("X-Api-Token");
        if (apiToken != null && !API_SERVICE_TOKEN.isEmpty()
                && java.security.MessageDigest.isEqual(
                        apiToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        API_SERVICE_TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            request.setAttribute("auth.role", "SERVICE");
            authenticate("service", "SERVICE");
            chain.doFilter(request, response);
            return;
        }

        String token = request.getHeader("X-Auth-Token");
        if (token == null || token.isEmpty()) token = request.getHeader("X-Student-Token");
        if (token == null || token.isEmpty()) token = request.getHeader("X-Admin-Token");
        boolean cookieAuthenticated = token == null || token.isEmpty();
        if (cookieAuthenticated) token = cookieToken(request);
        SessionStore.Session session = SessionStore.get(token);
        if (session == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未登录或会话已过期\"}");
            return;
        }
        if (cookieAuthenticated && !isSafeMethod(request.getMethod()) && !validCsrf(request)) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"CSRF 校验失败\"}");
            return;
        }
        SessionStore.touch(token);
        // 会话信息写入 request attribute，controller 可读（也保留各自校验作纵深防御）
        request.setAttribute("auth.role", session.role());
        request.setAttribute("auth.principal", session.principal());
        request.setAttribute("auth.token", token);
        authenticate(session.principal(), session.role());
        final String sessionToken = token;
        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                String original = super.getHeader(name);
                if (original != null && !original.isEmpty()) return original;
                if ("X-Auth-Token".equalsIgnoreCase(name)) return sessionToken;
                if ("X-Student-Token".equalsIgnoreCase(name) && "STUDENT".equals(session.role())) return sessionToken;
                if ("X-Admin-Token".equalsIgnoreCase(name) && !"STUDENT".equals(session.role())) return sessionToken;
                return original;
            }
        };
        chain.doFilter(wrapped, response);
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if ("EXAM_STUDENT_SESSION".equals(cookie.getName()) || "EXAM_ADMIN_SESSION".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean validCsrf(HttpServletRequest request) {
        String header = request.getHeader("X-CSRF-Token");
        if (header == null || header.isBlank() || request.getCookies() == null) return false;
        for (Cookie cookie : request.getCookies()) {
            if ("EXAM_CSRF".equals(cookie.getName())) {
                return java.security.MessageDigest.isEqual(
                        header.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        cookie.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    private boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private void authenticate(String principal, String role) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private boolean isPublic(String path) {
        for (String p : PUBLIC_PREFIXES) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

}
