package com.tft.batch.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import com.tft.batch.client.RiotLeagueClient;
import com.tft.batch.client.RiotMatchClient;
import com.tft.batch.client.RiotSummonerClient;
import com.tft.batch.client.dto.RiotMatchDetailResponse;
import com.tft.batch.ratelimit.RiotRateLimitedException;
import com.tft.batch.ratelimit.RiotRateLimiter;
import com.tft.batch.repository.GameInfoRepository;
import com.tft.batch.repository.LpHistoryRepository;

/** 수집 작업 하나를 처리할 때 오류 종류별로 작업이 어디로 가는지 검증한다. */
class MatchFetchServiceTest {

    private RedisQueueService queue;
    private RiotMatchClient matchClient;
    private MatchDetailSaveService saveService;
    private RiotRateLimiter rateLimiter;
    private MatchFetchService service;

    @BeforeEach
    void setUp() {
        queue = mock(RedisQueueService.class);
        matchClient = mock(RiotMatchClient.class);
        saveService = mock(MatchDetailSaveService.class);
        rateLimiter = mock(RiotRateLimiter.class);
        service = new MatchFetchService(queue, matchClient, mock(RiotSummonerClient.class), saveService,
                mock(GameInfoRepository.class), rateLimiter, mock(RiotLeagueClient.class),
                mock(LpHistoryRepository.class));
        when(queue.popTask()).thenReturn(new RedisQueueService.QueueTask("KR_1", "MATCH", 99));
    }

    @Test
    void savesMatchAndClearsRetryCountOnSuccess() {
        RiotMatchDetailResponse response = new RiotMatchDetailResponse();
        when(matchClient.fetchMatchDetail("KR_1")).thenReturn(response);

        service.fetchNext();

        verify(saveService).save(response);
        verify(queue).clearRetry(any());
        verify(queue, never()).pushTask(anyString(), anyString(), anyDouble());
    }

    @Test
    void doesNotTakeTaskWhileRateLimitBudgetIsExhausted() {
        when(rateLimiter.peekWaitMillis()).thenReturn(5_000L);

        service.fetchNext();

        verify(queue, never()).popTask();
    }

    @Test
    void requeuesWithSamePriorityOn429WithoutCountingAsFailure() {
        when(matchClient.fetchMatchDetail("KR_1")).thenThrow(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "", new HttpHeaders(), null, null));

        service.fetchNext();

        verify(queue).pushTask("KR_1", "MATCH", 99);
        verify(queue, never()).incrementRetry(any());
    }

    @Test
    void requeuesWhenBudgetRunsOutInTheMiddleOfTask() {
        when(matchClient.fetchMatchDetail("KR_1")).thenThrow(new RiotRateLimitedException(1_000));

        service.fetchNext();

        verify(queue).pushTask("KR_1", "MATCH", 99);
        verify(queue, never()).incrementRetry(any());
    }

    @Test
    void retriesTransientErrorWhileUnderLimit() {
        when(matchClient.fetchMatchDetail("KR_1")).thenThrow(
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "", new HttpHeaders(), null, null));
        when(queue.incrementRetry(any())).thenReturn(1L);

        service.fetchNext();

        verify(queue).pushTask("KR_1", "MATCH", 99);
        verify(queue, never()).markFailed(any(), anyString());
    }

    @Test
    void movesTaskToFailedListAfterMaxRetries() {
        when(matchClient.fetchMatchDetail("KR_1")).thenThrow(new RuntimeException("connection reset"));
        when(queue.incrementRetry(any())).thenReturn((long) MatchFetchService.MAX_RETRIES + 1);

        service.fetchNext();

        verify(queue, never()).pushTask(anyString(), anyString(), anyDouble());
        verify(queue).markFailed(any(), anyString());
    }

    @Test
    void doesNotRetryNotFound() {
        when(matchClient.fetchMatchDetail("KR_1")).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", new HttpHeaders(), null, null));

        service.fetchNext();

        verify(queue, never()).pushTask(anyString(), anyString(), anyDouble());
        verify(queue, never()).incrementRetry(any());
        verify(queue).markFailed(any(), anyString());
    }
}
