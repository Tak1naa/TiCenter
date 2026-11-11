package com.exambank.chat.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Distributed token buckets with an in-process fallback for developer/test profiles. */
@Service
public class DistributedRateLimiter implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimiter.class);
    private final RedisClient client;
    private final StatefulRedisConnection<String, byte[]> connection;
    private final ProxyManager<String> proxyManager;
    private final boolean redisRequired;
    private final Map<String, Bucket> local = new ConcurrentHashMap<>();

    public DistributedRateLimiter(@Value("${spring.data.redis.host:localhost}") String host,
                                  @Value("${spring.data.redis.port:6379}") int port,
                                  @Value("${spring.data.redis.password:}") String password,
                                  @Value("${app.session.redis-required:false}") boolean redisRequired) {
        this.redisRequired = redisRequired;
        RedisURI.Builder uri = RedisURI.builder().withHost(host).withPort(port).withTimeout(Duration.ofSeconds(2));
        if (password != null && !password.isBlank()) uri.withPassword(password.toCharArray());
        this.client = RedisClient.create(uri.build());
        StatefulRedisConnection<String, byte[]> opened = null;
        ProxyManager<String> manager = null;
        try {
            opened = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
            manager = Bucket4jLettuce.casBasedBuilder(opened)
                    .expirationAfterWrite(ExpirationAfterWriteStrategy
                            .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
                    .build();
        } catch (Exception e) {
            if (redisRequired) throw new IllegalStateException("Redis rate limiter unavailable", e);
            log.warn("Redis rate limiter unavailable; using local buckets: {}", e.getMessage());
        }
        this.connection = opened;
        this.proxyManager = manager;
    }

    public boolean allow(String scope, String identity, long capacity, long refillPerMinute) {
        String key = "exam:limit:" + scope + ":" + identity;
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity)
                        .refillGreedy(refillPerMinute, Duration.ofMinutes(1)).build())
                .build();
        try {
            Bucket bucket = proxyManager != null
                    ? proxyManager.builder().build(key, () -> configuration)
                    : local.computeIfAbsent(key, ignored -> Bucket.builder()
                        .addLimit(configuration.getBandwidths()[0]).build());
            return bucket.tryConsume(1);
        } catch (Exception e) {
            if (redisRequired) throw new IllegalStateException("Redis rate limiter unavailable", e);
            Bucket fallback = local.computeIfAbsent(key, ignored -> Bucket.builder()
                    .addLimit(configuration.getBandwidths()[0]).build());
            return fallback.tryConsume(1);
        }
    }

    @Override
    public void destroy() {
        if (connection != null) connection.close();
        client.shutdown();
    }
}
