package com.exambank.common.config;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 统一会话存储：学生与管理员共用（消除手写双份 Map）。
 * token → Session(role, principal, ip, expiry)，30 分钟滑动过期。
 */
public class SessionStore {

    public record Session(String role, String principal, String ip, Instant expiry) {
        public boolean expired() { return Instant.now().isAfter(expiry); }
    }

    private static final long TTL_MINUTES = 30;
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static volatile StringRedisTemplate redis;
    private static volatile boolean redisRequired;
    private static final String KEY_PREFIX = "exam:session:";

    private SessionStore() {}

    static void configureRedis(StringRedisTemplate template, boolean required) {
        redis = template;
        redisRequired = required;
    }

    public static String create(String role, String principal, String ip) {
        String token = com.exambank.common.util.TokenGenerator.generate();
        Session session = new Session(role, principal, ip, Instant.now().plusSeconds(TTL_MINUTES * 60));
        if (!writeRedis(token, session)) sessions.put(token, session);
        return token;
    }

    public static Session get(String token) {
        if (token == null) return null;
        Session s = readRedis(token);
        if (s == null) s = sessions.get(token);
        if (s == null) return null;
        if (s.expired()) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    /** 访问时滑动续期（并发安全：仅当仍是同一会话时刷新）。 */
    public static void touch(String token) {
        if (redis != null) {
            try {
                String key = redisKey(token);
                Boolean exists = redis.hasKey(key);
                if (Boolean.TRUE.equals(exists)) redis.expire(key, Duration.ofMinutes(TTL_MINUTES));
            } catch (Exception e) {
                if (redisRequired) throw new IllegalStateException("Redis session store unavailable", e);
            }
        }
        sessions.computeIfPresent(token, (k, s) ->
                s.expired() ? null : new Session(s.role(), s.principal(), s.ip(), Instant.now().plusSeconds(TTL_MINUTES * 60)));
    }

    public static void remove(String token) {
        if (token != null) {
            sessions.remove(token);
            if (redis != null) {
                try {
                    redis.delete(redisKey(token));
                    // Remove keys produced by versions that embedded the bearer token directly.
                    redis.delete(KEY_PREFIX + token);
                }
                catch (Exception e) { if (redisRequired) throw new IllegalStateException("Redis session store unavailable", e); }
            }
        }
    }

    /** 使某主体的全部会话失效（改密/删除账号时用）。 */
    public static void invalidateByPrincipal(String role, String principal) {
        sessions.entrySet().removeIf(e -> e.getValue().role().equals(role)
                && e.getValue().principal().equals(principal));
        if (redis != null) {
            try {
                var keys = redis.keys(KEY_PREFIX + "*");
                if (keys != null) for (String key : keys) {
                    String encoded = redis.opsForValue().get(key);
                    Session session = encoded == null ? null : decode(encoded);
                    if (session != null && session.role().equals(role) && session.principal().equals(principal)) {
                        redis.delete(key);
                    }
                }
            } catch (Exception e) {
                if (redisRequired) throw new IllegalStateException("Redis session store unavailable", e);
            }
        }
    }

    private static boolean writeRedis(String token, Session session) {
        if (redis == null) return false;
        try {
            redis.opsForValue().set(redisKey(token), encode(session), Duration.ofMinutes(TTL_MINUTES));
            return true;
        } catch (Exception e) {
            if (redisRequired) throw new IllegalStateException("Redis session store unavailable", e);
            return false;
        }
    }

    private static Session readRedis(String token) {
        if (redis == null || token == null) return null;
        try {
            String value = redis.opsForValue().get(redisKey(token));
            if (value == null) {
                // One-time compatibility migration from legacy raw-token Redis keys.
                String legacyKey = KEY_PREFIX + token;
                value = redis.opsForValue().get(legacyKey);
                if (value != null) {
                    redis.opsForValue().set(redisKey(token), value, Duration.ofMinutes(TTL_MINUTES));
                    redis.delete(legacyKey);
                }
            }
            return value == null ? null : decode(value);
        } catch (Exception e) {
            if (redisRequired) throw new IllegalStateException("Redis session store unavailable", e);
            return null;
        }
    }

    private static String redisKey(String token) {
        return KEY_PREFIX + com.exambank.common.util.TokenGenerator.digest(token);
    }

    private static String encode(Session session) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return session.role() + "." + encoder.encodeToString(session.principal().getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString((session.ip() == null ? "" : session.ip()).getBytes(StandardCharsets.UTF_8));
    }

    private static Session decode(String value) {
        String[] parts = value.split("\\.", 3);
        if (parts.length != 3) return null;
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return new Session(parts[0], new String(decoder.decode(parts[1]), StandardCharsets.UTF_8),
                new String(decoder.decode(parts[2]), StandardCharsets.UTF_8),
                Instant.now().plusSeconds(TTL_MINUTES * 60));
    }
}
