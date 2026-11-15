package com.gatekeeper.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class RateLimiterService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> tokenBucketScript;

    public RateLimiterService(StringRedisTemplate redis) {
        this.redis = redis;
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setLocation(new ClassPathResource("token_bucket.lua"));
        this.tokenBucketScript.setResultType(Long.class);
    }

    /**
     * Token-bucket rate limit check. Returns true if request allowed.
     */
    public boolean allow(String apiKeyId, int limitPerMin) {
        String bucketKey = "bucket:" + apiKeyId;
        double refillRatePerSecond = limitPerMin / 60.0;
        long now = System.currentTimeMillis();

        Long result = redis.execute(
                tokenBucketScript,
                Collections.singletonList(bucketKey),
                String.valueOf(limitPerMin),
                String.valueOf(refillRatePerSecond),
                String.valueOf(now)
        );

        return result != null && result == 1L;
    }
}