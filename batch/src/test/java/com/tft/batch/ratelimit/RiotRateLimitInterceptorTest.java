package com.tft.batch.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RiotRateLimitInterceptorTest {

    @Test
    void followsValidRetryAfterWithoutShortening() {
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("37")).isEqualTo(37);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds(" 5 ")).isEqualTo(5);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("600")).isEqualTo(600);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("3600")).as("600초보다 길어도 줄이지 않는다").isEqualTo(3600);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("0")).as("최소 1초").isEqualTo(1);
    }

    @Test
    void readsHttpDateFormRetryAfter() {
        String inTwoMinutes = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(120));

        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds(inTwoMinutes)).isBetween(115L, 120L);
    }

    @Test
    void blocksLongerWhenRetryAfterIsUnusable() {
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds(null)).as("헤더 없음").isEqualTo(10);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("soon")).isEqualTo(60);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("")).isEqualTo(60);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("-3")).isEqualTo(60);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT")).as("지난 날짜").isEqualTo(60);
        assertThat(RiotRateLimitInterceptor.parseRetryAfterSeconds("99999999999")).as("하루 초과는 하루").isEqualTo(86_400);
    }
}
