package com.tft.web.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Web 서버의 Riot API 호출을 Batch와 같은 Redis 키에 기록한다.
 *
 * 사용자 요청은 기다리게 하지 않고 기록만 한다. Batch의 제한기(RiotRateLimiter)가
 * 이 기록을 함께 세어, Web이 쓴 만큼 수집 속도를 늦춘다.
 * 키 이름과 구간(2분)은 batch 모듈의 RiotRateLimiter와 같아야 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RiotCallRecorder implements ClientHttpRequestInterceptor {

    private static final String CALLS_KEY = "riot:ratelimit:calls";
    private static final String BLOCKED_KEY = "riot:ratelimit:blocked";
    private static final long WINDOW_MS = 120_000;
    private static final long DEFAULT_RETRY_AFTER_SEC = 10;

    private static final DefaultRedisScript<Long> RECORD = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - tonumber(ARGV[1]))
            redis.call('ZADD', KEYS[1], now, now .. ':' .. ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String host = request.getURI().getHost();
        boolean riotCall = host != null && host.endsWith("api.riotgames.com");
        if (riotCall) {
            record();
        }

        ClientHttpResponse response = execution.execute(request, body);
        if (riotCall && response.getStatusCode().value() == 429) {
            long retryAfter = parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
            log.warn("Rate limit exceeded. Blocking Riot calls for {}s", retryAfter);
            redis.opsForValue().set(BLOCKED_KEY, "1", Duration.ofSeconds(retryAfter + 1));
        }
        return response;
    }

    private void record() {
        try {
            redis.execute(RECORD, List.of(CALLS_KEY), String.valueOf(WINDOW_MS), UUID.randomUUID().toString());
        } catch (Exception e) {
            // 기록 실패가 사용자 요청을 막아서는 안 된다
            log.warn("Failed to record Riot API call: {}", e.getMessage());
        }
    }

    private long parseRetryAfter(String header) {
        try {
            return header != null ? Long.parseLong(header.trim()) : DEFAULT_RETRY_AFTER_SEC;
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_AFTER_SEC;
        }
    }
}
