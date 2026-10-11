package com.tft.batch.ratelimit;

/** 호출 한도가 차서 지금은 Riot API를 부를 수 없을 때 던진다. */
public class RiotRateLimitedException extends RuntimeException {

    private final long waitMillis;

    public RiotRateLimitedException(long waitMillis) {
        super("Riot API rate limit budget exhausted, retry after " + waitMillis + " ms");
        this.waitMillis = waitMillis;
    }

    public long getWaitMillis() {
        return waitMillis;
    }
}
