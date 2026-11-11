package com.exambank.common.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks IP access counts for admin dashboard.
 */
public class IpTrackingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(IpTrackingFilter.class);

    public static final Map<String, AtomicInteger> ipAccessCount = new ConcurrentHashMap<>();
    public static final Map<String, String> ipLastAccess = new ConcurrentHashMap<>();
    public static final Map<String, String> ipFirstAccess = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String ip = getClientIp(req);
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        ipAccessCount.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        ipFirstAccess.putIfAbsent(ip, now);
        ipLastAccess.put(ip, now);
        cleanupIfNeeded();

        chain.doFilter(request, response);
    }

    /** 上限保护：IP 统计 Map 无限增长会泄漏内存（公网可被伪造 IP 灌满）。
     *  超过阈值时清空重建——统计本身是监控用途，丢失历史可接受。 */
    private static final int MAX_IP_ENTRIES = 50_000;

    private static void cleanupIfNeeded() {
        if (ipAccessCount.size() > MAX_IP_ENTRIES) {
            synchronized (IpTrackingFilter.class) {
                if (ipAccessCount.size() > MAX_IP_ENTRIES) {
                    log.warn("IP tracking map exceeded {} entries, resetting", MAX_IP_ENTRIES);
                    ipAccessCount.clear();
                    ipFirstAccess.clear();
                    ipLastAccess.clear();
                }
            }
        }
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
