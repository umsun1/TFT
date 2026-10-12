package com.tft.web.ratelimit;

import java.io.IOException;
import java.time.Duration;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Web 서버의 모든 Riot API 호출이 지나는 한 곳(riotRestTemplate에 등록).
 *
 * 호출 직전에 제한기를 통과시키고, 통과하지 못하면 HTTP 요청을 보내지 않는다.
 * 캐시에서 값을 돌려주는 경우에는 RestTemplate까지 오지 않으므로 한도를 쓰지 않는다.
 * 그래도 429가 오면 Retry-After 동안 Web/Batch 공용 차단을 건다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RiotRateLimitInterceptor implements ClientHttpRequestInterceptor {

    static final long DEFAULT_RETRY_AFTER_SEC = 10;          // 헤더가 없을 때
    static final long UNUSABLE_RETRY_AFTER_SEC = 60;         // 헤더가 있지만 해석할 수 없을 때
    static final long LONGEST_DOCUMENTED_WINDOW_SEC = 600;   // 프로덕션 키의 10분 구간
    static final long MAX_RETRY_AFTER_SEC = 86_400;          // 헤더 오류에 대한 안전장치(하루)

    private final RiotRateLimiter rateLimiter;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String host = request.getURI().getHost();
        if (host == null || !host.endsWith("api.riotgames.com")) {
            return execution.execute(request, body);
        }

        rateLimiter.acquire(); // 통과 못 하면 여기서 예외. 아래 HTTP 호출은 일어나지 않는다.

        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() == 429) {
            long retryAfter = parseRetryAfterSeconds(response.getHeaders().getFirst("Retry-After"));
            log.warn("Rate limit exceeded (type={}). Blocking Riot calls for {}s",
                    response.getHeaders().getFirst("X-Rate-Limit-Type"), retryAfter);
            try {
                rateLimiter.blockFor(Duration.ofSeconds(retryAfter + 1));
            } catch (RuntimeException e) {
                // 차단 기록에 실패해도 429 응답은 그대로 호출한 쪽에 전달한다
                log.warn("Failed to record Riot block: {}", e.getMessage());
            }
        }
        return response;
    }

    /**
     * Retry-After를 초 단위로 읽는다. Riot 문서: "Retry-After가 가리키는 시간 동안 호출을 멈춰야 한다."
     *
     * - 양의 정수: 그대로 따른다(줄이지 않는다). 문서상 가장 긴 구간(600초)을 넘으면 오류 로그를 남긴다.
     * - HTTP 날짜 형식: 그 시각까지 남은 초.
     * - 0: 최소 1초.
     * - 헤더 없음: 하위 서비스 한도일 때 생기며 대기 시간 규정이 없다. 기본 10초.
     * - 음수, 숫자·날짜가 아닌 값, 지난 날짜: 얼마나 기다려야 하는지 알 수 없으므로 더 길게(60초) 멈춘다.
     * - 하루를 넘는 값: 정상적인 한도 구간으로 볼 수 없어 하루만 차단하고 오류 로그를 남긴다.
     *   (값을 그대로 따르면 잘못된 헤더 하나로 서비스가 무기한 멈추므로 둔 유일한 상한이다.)
     */
    static long parseRetryAfterSeconds(String header) {
        if (header == null) {
            return DEFAULT_RETRY_AFTER_SEC;
        }
        String value = header.trim();
        Long seconds = null;
        try {
            seconds = Long.parseLong(value);
        } catch (NumberFormatException notANumber) {
            try {
                java.time.ZonedDateTime until = java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                seconds = java.time.Duration.between(java.time.ZonedDateTime.now(until.getZone()), until).getSeconds();
            } catch (java.time.format.DateTimeParseException notADate) {
                // 아래에서 처리
            }
        }
        if (seconds == null || seconds < 0) {
            log.warn("Unusable Retry-After header '{}'. Blocking for {}s", header, UNUSABLE_RETRY_AFTER_SEC);
            return UNUSABLE_RETRY_AFTER_SEC;
        }
        if (seconds > MAX_RETRY_AFTER_SEC) {
            log.error("Retry-After {}s exceeds one day. Capping at {}s", seconds, MAX_RETRY_AFTER_SEC);
            return MAX_RETRY_AFTER_SEC;
        }
        if (seconds > LONGEST_DOCUMENTED_WINDOW_SEC) {
            log.error("Retry-After {}s is longer than any documented rate limit window. Riot calls stay blocked.", seconds);
        }
        return Math.max(1, seconds);
    }
}
