package com.tft.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * 가짜 Riot 서버(MockRestServiceServer)를 붙여, 제한기가 막으면 HTTP 요청이 실제로 나가지 않는지 확인한다.
 */
class RiotRateLimitInterceptorTest {

    private static final String RIOT_URL = "https://kr.api.riotgames.com/tft/league/v1/by-puuid/abc";

    private RiotRateLimiter limiter;
    private RestTemplate restTemplate;
    private MockRestServiceServer riot;

    @BeforeEach
    void setUp() {
        limiter = mock(RiotRateLimiter.class);
        restTemplate = new RestTemplate();
        restTemplate.setInterceptors(List.of(new RiotRateLimitInterceptor(limiter)));
        riot = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void sendsRequestWhenLimiterAllows() {
        riot.expect(once(), requestTo(RIOT_URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(restTemplate.getForObject(RIOT_URL, String.class)).isEqualTo("[]");

        verify(limiter).acquire();
        riot.verify();
    }

    @Test
    void doesNotSendRequestWhenLimitIsReached() {
        doThrow(new RiotRateLimitedException(42_000, false, null)).when(limiter).acquire();
        riot.expect(org.springframework.test.web.client.ExpectedCount.never(), requestTo(RIOT_URL));

        assertThatThrownBy(() -> restTemplate.getForObject(RIOT_URL, String.class))
                .isInstanceOfSatisfying(RiotRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(42));

        riot.verify(); // Riot으로 나간 요청 0건
    }

    @Test
    void doesNotSendRequestWhenLimiterIsUnavailable() {
        doThrow(new RiotRateLimitedException(5_000, true, new IllegalStateException("redis down")))
                .when(limiter).acquire();
        riot.expect(org.springframework.test.web.client.ExpectedCount.never(), requestTo(RIOT_URL));

        assertThatThrownBy(() -> restTemplate.getForObject(RIOT_URL, String.class))
                .isInstanceOfSatisfying(RiotRateLimitedException.class,
                        e -> assertThat(e.isLimiterUnavailable()).isTrue());

        riot.verify();
    }

    @Test
    void recordsBlockForRetryAfterOn429() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "37");
        riot.expect(once(), requestTo(RIOT_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(() -> restTemplate.getForObject(RIOT_URL, String.class))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);

        ArgumentCaptor<Duration> blocked = ArgumentCaptor.forClass(Duration.class);
        verify(limiter).blockFor(blocked.capture());
        assertThat(blocked.getValue()).isEqualTo(Duration.ofSeconds(38)); // Retry-After + 1초 여유
    }

    @Test
    void usesDefaultBlockWhenRetryAfterHeaderIsMissing() {
        riot.expect(once(), requestTo(RIOT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> restTemplate.getForObject(RIOT_URL, String.class))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);

        ArgumentCaptor<Duration> blocked = ArgumentCaptor.forClass(Duration.class);
        verify(limiter).blockFor(blocked.capture());
        assertThat(blocked.getValue())
                .isEqualTo(Duration.ofSeconds(RiotRateLimitInterceptor.DEFAULT_RETRY_AFTER_SEC + 1));
    }

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

    @Test
    void doesNotBlockOnOtherErrors() {
        riot.expect(once(), requestTo(RIOT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> restTemplate.getForObject(RIOT_URL, String.class))
                .isInstanceOf(HttpClientErrorException.NotFound.class);

        verify(limiter, org.mockito.Mockito.never()).blockFor(any());
    }

    @Test
    void doesNotLimitCallsToOtherHosts() {
        String url = "https://ddragon.leagueoflegends.com/cdn/data.json";
        riot.expect(once(), requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        restTemplate.getForObject(url, String.class);

        verify(limiter, org.mockito.Mockito.never()).acquire();
    }
}
