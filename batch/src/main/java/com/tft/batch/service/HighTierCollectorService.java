package com.tft.batch.service;

import com.tft.batch.client.RiotLeagueClient;
import com.tft.batch.client.dto.TftLeagueItemDto;
import com.tft.batch.client.dto.TftLeagueListDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HighTierCollectorService {

    private final RiotLeagueClient riotLeagueClient;
    private final RedisQueueService redisQueueService;
    private final com.tft.batch.repository.LpHistoryRepository lpHistoryRepository;

    @Transactional
    public void collectHighTierPlayers() {
        log.info("Starting High Tier player collection...");

        // 1. Challenger
        try {
            collectLeague(riotLeagueClient.getChallengerLeague(), "CHALLENGER", 100);
        } catch (Exception e) {
            log.error("Failed to collect Challenger league: {}", e.getMessage());
        }

        // 2. Grandmaster
        try {
            collectLeague(riotLeagueClient.getGrandmasterLeague(), "GRANDMASTER", 80);
        } catch (Exception e) {
            log.error("Failed to collect Grandmaster league: {}", e.getMessage());
        }

        // 3. Master
        try {
            // collectLeague(riotLeagueClient.getMasterLeague(), "MASTER", 50);
        } catch (Exception e) {
            log.error("Failed to collect Master league: {}", e.getMessage());
        }

        log.info("High Tier player collection finished.");
    }

    private void collectLeague(TftLeagueListDto league, String tierName, int priority) {
        if (league == null || league.getEntries() == null) {
            log.warn("League data is null or empty for {}", tierName);
            return;
        }

        if (!league.getEntries().isEmpty()) {
            log.info("Sample first entry puuid: {}", league.getEntries().get(0).getPuuid());
        }

        int count = 0;
        for (TftLeagueItemDto entry : league.getEntries()) {
            // Case 1: PUUID가 있는 경우 (바로 랭킹 등록 및 매치 수집)
            if (entry.getPuuid() != null) {
                redisQueueService.pushTask(entry.getPuuid(), "SUMMONER", priority);
                count++;
                
                try {
                    saveLpHistory(entry, tierName);
                } catch (Exception e) {
                    log.error("Failed to save LP history for {}: {}", entry.getPuuid(), e.getMessage());
                }
            } 
            // Case 2: PUUID가 없고 SummonerID만 있는 경우 (ID 변환 대기열에 등록)
            else if (entry.getSummonerId() != null) {
                redisQueueService.pushTask(entry.getSummonerId(), "SUMMONER_ID", priority);
                count++;
            }
        }
        log.info("Added {} new players to queue from {}", count, tierName);
    }

    private void saveLpHistory(TftLeagueItemDto entry, String tier) {
        com.tft.batch.model.entity.LpHistory lastRecord = lpHistoryRepository.findTopByPuuidOrderByCreatedAtDesc(entry.getPuuid());

        boolean needUpdate = false;
        if (lastRecord == null) {
            needUpdate = true;
        } else {
            // 점수나 티어가 바뀌었을 때만 저장
            if (lastRecord.getLp() != entry.getLeaguePoints() || !lastRecord.getTier().equals(tier)) {
                needUpdate = true;
            }
        }

        if (needUpdate) {
            lpHistoryRepository.save(com.tft.batch.model.entity.LpHistory.builder()
                    .puuid(entry.getPuuid())
                    .tier(tier)
                    .rank_str(entry.getRank())
                    .lp(entry.getLeaguePoints())
                    .createdAt(LocalDateTime.now())
                    .build());
        }
    }
}
