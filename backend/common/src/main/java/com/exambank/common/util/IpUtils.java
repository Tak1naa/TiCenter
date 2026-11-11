package com.exambank.common.util;

import jakarta.servlet.http.HttpServletRequest;

public class IpUtils {

    /** 仅当部署在可信反向代理（nginx）之后且显式设置 TRUST_PROXY_HEADERS=true 时，
     *  才信任 X-Real-IP / X-Forwarded-For。否则直接连入时这些头可被客户端伪造，
     *  用于绕过按 IP 的限流。dev 直连默认不信任（所有请求记 127.0.0.1）。 */
    private static final boolean TRUST_PROXY_HEADERS =
            "true".equalsIgnoreCase(System.getenv("TRUST_PROXY_HEADERS"));

    private IpUtils() {}

    public static String getClientIp(HttpServletRequest request) {
        if (TRUST_PROXY_HEADERS) {
            String ip = request.getHeader("X-Real-IP");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Forwarded-For");
            }
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim(); // 取最左（nginx 追加的是 $remote_addr）
            }
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip;
            }
        }
        return request.getRemoteAddr();
    }
}
