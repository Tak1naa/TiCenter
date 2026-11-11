package com.exambank.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisSessionBridge {
    public RedisSessionBridge(StringRedisTemplate redisTemplate,
                              @Value("${app.session.redis-required:false}") boolean required) {
        SessionStore.configureRedis(redisTemplate, required);
    }
}
