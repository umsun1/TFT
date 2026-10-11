package com.tft.batch.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tft.batch.service.MatchFetchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class MatchFetchScheduler {

    private final MatchFetchService matchFetchService;

    // 호출 속도는 RiotRateLimiter가 제어한다. 이 간격은 대기열을 확인하는 주기일 뿐이다.
    @Scheduled(fixedDelayString = "${batch.match-fetch.delay-ms:1000}")
    public void run() {
        log.info("match fetch scheduler start");
        matchFetchService.fetchNext();
    }
}