package com.tft.web.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Web 서버가 Riot API를 부르기 전에 통과해야 하는 제한기.
 *
 * batch 모듈의 RiotRateLimiter와 같은 Redis 키, 같은 Lua 스크립트를 쓴다(공통 모듈이 없어 복사해 둠).
 * 두 서버의 호출이 한 ZSet에 기록되므로 "최근 2분 / 최근 1초"는 Web과 Batch를 합친 호출 수다.
 * 한도만 다르다: Batch는 합계 80(1초 10)에서 멈추고, Web은 합계 95(1초 15)까지 쓴다. 그래서 사용자 요청 몫이 항상 남는다.
 * 설정값은 Riot 한도(2분 100, 1초 20)보다 낮게 잡은 목표치다. 제한기는 허가한 시각으로 세고 Riot은 도착한 시각으로 세므로
 * 도착 기준으로는 설정값보다 1~2회 많게 관측될 수 있다(측정: 95 → 96, 10 → 11).
 *
 * Batch와 다른 점: 절대 기다리지 않는다. 통과 못 하면 남은 시간과 함께 예외를 던지고,
 * Redis에 문제가 있어 한도를 확인할 수 없을 때도 호출을 막는다(fail-closed).
 *
 * 키 이름이나 ACQUIRE/BLOCK 스크립트를 바꿀 때는 batch 쪽도 똑같이 바꿔야 한다.
 */
@Component
public class RiotRateLimiter {

    static final String CALLS_KEY = "riot:ratelimit:calls";
    static final String BLOCKED_KEY = "riot:ratelimit:blocked";

    // Redis를 쓸 수 없을 때 안내할 대기 시간. 정확한 값을 알 수 없어 짧은 고정값을 쓴다.
    static final long UNAVAILABLE_RETRY_MS = 5_000;

    // 반환값: 0이면 통과(호출 1건 기록됨), 양수면 그만큼(ms) 뒤에 다시 시도
    // 확인과 기록이 스크립트 하나라 Redis가 한 번에 하나씩만 실행한다(동시 요청이 같은 빈자리를 둘이 차지할 수 없음)
    static final String ACQUIRE_SCRIPT = """
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

    // 차단 시간은 늘어나는 방향으로만 바꾼다. 긴 차단이 걸려 있는데 짧은 값이 뒤늦게 와도 줄어들지 않는다.
    static final String BLOCK_SCRIPT = """
            local ttl = redis.call('PTTL', KEYS[1])
            local want = tonumber(ARGV[1])
            if ttl < want then
              redis.call('SET', KEYS[1], '1', 'PX', want)
              return want
            end
            return ttl
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> acquireScript = new DefaultRedisScript<>(ACQUIRE_SCRIPT, Long.class);
    private final DefaultRedisScript<Long> blockScript = new DefaultRedisScript<>(BLOCK_SCRIPT, Long.class);
    private final List<String> keys;
    private final long longWindowMs;
    private final int longLimit;
    private final long shortWindowMs;
    private final int shortLimit;

    @Autowired
    public RiotRateLimiter(StringRedisTemplate redis,
            @Value("${riot.rate-limit.web-per-two-minutes:95}") int longLimit,
            @Value("${riot.rate-limit.web-per-second:15}") int shortLimit) {
        this(redis, "", 120_000, longLimit, 1_000, shortLimit);
    }

    RiotRateLimiter(StringRedisTemplate redis, String keyPrefix, long longWindowMs, int longLimit,
            long shortWindowMs, int shortLimit) {
        this.redis = redis;
        this.keys = List.of(keyPrefix + CALLS_KEY, keyPrefix + BLOCKED_KEY);
        this.longWindowMs = longWindowMs;
        this.longLimit = longLimit;
        this.shortWindowMs = shortWindowMs;
        this.shortLimit = shortLimit;
    }

    /**
     * 호출 1건을 확보한다. 통과하면 그대로 반환하고, 못 하면 기다리지 않고 예외를 던진다.
     *
     * @throws RiotRateLimitedException 한도 소진, 429 차단 중, 또는 Redis 장애로 확인 불가
     */
    public void acquire() {
        long wait;
        try {
            Long result = redis.execute(acquireScript, keys,
                    String.valueOf(longWindowMs), String.valueOf(longLimit),
                    String.valueOf(shortWindowMs), String.valueOf(shortLimit),
                    "1", UUID.randomUUID().toString());
            // 결과를 읽지 못했다면 기록 여부를 알 수 없으므로 통과시키지 않는다
            if (result == null) {
                throw new RiotRateLimitedException(UNAVAILABLE_RETRY_MS, true, null);
            }
            wait = result;
        } catch (RiotRateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RiotRateLimitedException(UNAVAILABLE_RETRY_MS, true, e);
        }
        if (wait > 0) {
            throw new RiotRateLimitedException(wait, false, null);
        }
    }

    /**
     * 429를 받았을 때: 주어진 시간 동안 Web/Batch 모두 호출하지 않도록 차단한다.
     * 이미 더 긴 차단이 걸려 있으면 그대로 둔다.
     */
    public void blockFor(Duration duration) {
        redis.execute(blockScript, List.of(keys.get(1)), String.valueOf(duration.toMillis()));
    }
}
