package com.tft.web.ratelimit;

/**
 * 지금은 Riot API를 부를 수 없어 호출 전에 막았을 때 던진다. (HTTP 요청은 나가지 않았다)
 */
public class RiotRateLimitedException extends RuntimeException {

    private final long retryAfterMillis;
    private final boolean limiterUnavailable;

    public RiotRateLimitedException(long retryAfterMillis, boolean limiterUnavailable, Throwable cause) {
        super(limiterUnavailable
                ? "Riot API rate limiter unavailable (Redis). Call blocked."
                : "Riot API rate limit reached. Retry after " + retryAfterMillis + " ms", cause);
        this.retryAfterMillis = retryAfterMillis;
        this.limiterUnavailable = limiterUnavailable;
    }

    /** 다시 시도해 볼 만한 시점까지 남은 시간(ms). 자리 1개가 비는 시점이라 보장값은 아니다. */
    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    /** 안내용: 올림한 초 단위. */
    public long getRetryAfterSeconds() {
        return Math.max(1, (retryAfterMillis + 999) / 1000);
    }

    /** 한도 소진이 아니라 Redis 장애로 한도를 확인하지 못해 막은 경우. */
    public boolean isLimiterUnavailable() {
        return limiterUnavailable;
    }
}
