package com.tft.batch.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Riot API 호출 전에 통과해야 하는 사전 제한기.
 *
 * 호출 시각을 Redis ZSet에 기록해 "최근 2분", "최근 1초" 구간의 호출 수를 센다(슬라이딩 윈도우).
 * Web 서버도 같은 키에 자신의 호출을 기록하므로, Batch는 Web이 쓴 만큼 덜 쓴다.
 * 429를 받으면 Retry-After 동안 차단 키를 걸어 그 시간에는 아무도 호출하지 않는다.
 */
@Component
public class RiotRateLimiter {

    static final String CALLS_KEY = "riot:ratelimit:calls";
    static final String BLOCKED_KEY = "riot:ratelimit:blocked";

    // 반환값: 0이면 통과, 양수면 그만큼(ms) 기다려야 함
    // 시각은 Redis TIME을 써서 Web/Batch 서버의 시계 차이와 무관하게 한다
    private static final String SCRIPT = """
            local blocked = redis.call('PTTL', KEYS[2])
            if blocked > 0 then return blocked end

            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local longWindow, longLimit = tonumber(ARGV[1]), tonumber(ARGV[2])
            local shortWindow, shortLimit = tonumber(ARGV[3]), tonumber(ARGV[4])

            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - longWindow)
            local longCount = redis.call('ZCARD', KEYS[1])
            if longCount >= longLimit then
              local idx = longCount - longLimit
              local e = redis.call('ZRANGE', KEYS[1], idx, idx, 'WITHSCORES')
              return math.max(1, tonumber(e[2]) + longWindow - now)
            end

            local shortCount = redis.call('ZCOUNT', KEYS[1], now - shortWindow, '+inf')
            if shortCount >= shortLimit then return shortWindow end

            if ARGV[5] == '1' then
              redis.call('ZADD', KEYS[1], now, now .. ':' .. ARGV[6])
              redis.call('PEXPIRE', KEYS[1], longWindow)
            end
            return 0
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(SCRIPT, Long.class);
    private final List<String> keys;
    private final long longWindowMs;
    private final int longLimit;
    private final long shortWindowMs;
    private final int shortLimit;
    private final long maxWaitMs;
    // 랭커 수집·LP 갱신처럼 여러 명을 차례로 도는 작업은 한도가 찰 때 건너뛰지 않고 기다려야 한다
    private final ThreadLocal<Boolean> patient = ThreadLocal.withInitial(() -> false);

    @Autowired
    public RiotRateLimiter(StringRedisTemplate redis,
            @Value("${riot.rate-limit.batch-per-two-minutes:80}") int longLimit,
            @Value("${riot.rate-limit.per-second:18}") int shortLimit,
            @Value("${riot.rate-limit.max-wait-ms:3000}") long maxWaitMs) {
        this(redis, "", 120_000, longLimit, 1_000, shortLimit, maxWaitMs);
    }

    RiotRateLimiter(StringRedisTemplate redis, String keyPrefix, long longWindowMs, int longLimit,
            long shortWindowMs, int shortLimit, long maxWaitMs) {
        this.redis = redis;
        this.keys = List.of(keyPrefix + CALLS_KEY, keyPrefix + BLOCKED_KEY);
        this.longWindowMs = longWindowMs;
        this.longLimit = longLimit;
        this.shortWindowMs = shortWindowMs;
        this.shortLimit = shortLimit;
        this.maxWaitMs = maxWaitMs;
    }

    /** 호출 1건을 기록하고 0을 반환한다. 한도가 찼으면 기록하지 않고 기다려야 할 시간(ms)을 반환한다. */
    public long tryAcquire() {
        return run(true);
    }

    /** 기록하지 않고 지금 호출할 수 있는지만 본다. 0이면 가능. */
    public long peekWaitMillis() {
        return run(false);
    }

    /**
     * 이 안에서 일어나는 Riot 호출은 한도가 비워질 때까지 기다린다.
     * 대기열 작업과 달리 되돌려 둘 곳이 없는 일괄 작업(랭커 수집, LP 갱신)에 쓴다.
     */
    public void runPatiently(Runnable job) {
        patient.set(true);
        try {
            job.run();
        } finally {
            patient.remove();
        }
    }

    /**
     * 호출 1건을 확보한다. 잠깐(maxWaitMs 이내) 기다리면 되는 경우에만 기다리고,
     * 그보다 오래 걸리면 예외를 던져 호출한 쪽이 작업을 미루게 한다.
     * runPatiently 안에서는 한도가 비워질 때까지 기다린다.
     */
    public void acquire() {
        long waited = 0;
        while (true) {
            long wait = tryAcquire();
            if (wait == 0) {
                return;
            }
            if (!patient.get() && waited + wait > maxWaitMs) {
                throw new RiotRateLimitedException(wait);
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RiotRateLimitedException(wait);
            }
            waited += wait;
        }
    }

    /** 429를 받았을 때: 주어진 시간 동안 Web/Batch 모두 호출하지 않도록 차단한다. */
    public void blockFor(Duration duration) {
        redis.opsForValue().set(keys.get(1), "1", duration);
    }

    private long run(boolean record) {
        Long result = redis.execute(script, keys,
                String.valueOf(longWindowMs), String.valueOf(longLimit),
                String.valueOf(shortWindowMs), String.valueOf(shortLimit),
                record ? "1" : "0", UUID.randomUUID().toString());
        return result == null ? 0 : result;
    }
}
