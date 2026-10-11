package com.tft.batch.ratelimit;

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
 * 모든 Riot API 호출이 지나는 한 곳. 호출 전에 제한기를 통과시키고,
 * 그래도 429가 오면 Retry-After 동안 차단을 건다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RiotRateLimitInterceptor implements ClientHttpRequestInterceptor {

    private static final long DEFAULT_RETRY_AFTER_SEC = 10;

    private final RiotRateLimiter rateLimiter;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String host = request.getURI().getHost();
        if (host == null || !host.endsWith("api.riotgames.com")) {
            return execution.execute(request, body);
        }

        rateLimiter.acquire();

        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() == 429) {
            long retryAfter = parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
            log.warn("Rate limit exceeded. Blocking Riot calls for {}s", retryAfter);
            rateLimiter.blockFor(Duration.ofSeconds(retryAfter + 1));
        }
        return response;
    }

    private long parseRetryAfter(String header) {
        try {
            return header != null ? Long.parseLong(header.trim()) : DEFAULT_RETRY_AFTER_SEC;
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_AFTER_SEC;
        }
    }
}
