package com.tft.batch.scheduler;

import com.tft.batch.service.LpUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LpUpdateScheduler {

    private final LpUpdateService lpUpdateService;

    // Run every 1 minute
    @Scheduled(fixedDelay = 60000)
    public void run() {
        log.info("Starting LP Update Scheduler...");
        lpUpdateService.updateActiveSummonersLp();
        log.info("LP Update Scheduler finished.");
    }
}
