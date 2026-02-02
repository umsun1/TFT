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

    @Scheduled(fixedDelay = 2000)
    public void run() {
        log.info("match fetch scheduler start");
        matchFetchService.fetchNext();
    }
}