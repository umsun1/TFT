package com.tft.batch.service;

import com.tft.batch.client.RiotLeagueClient;
import com.tft.batch.client.dto.TftLeagueEntryDto;
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
        int masterCount = 0;
        try {
            TftLeagueListDto masterLeague = riotLeagueClient.getMasterLeague();
            if (masterLeague != null && masterLeague.getEntries() != null && !masterLeague.getEntries().isEmpty()) {
                masterCount = collectLeague(masterLeague, "MASTER", 50);
            }
        } catch (Exception e) {
            log.error("Failed to collect Master league: {}", e.getMessage());
        }

        // 4. 시즌 초기 대응: 고티어 유저가 너무 적을 경우 에메랄드 I 유저 수집 (임시)
        if (masterCount < 10) {
            log.info("Very few Master players found. Collecting Emerald I players for season start...");
            try {
                List<TftLeagueEntryDto> emeraldEntries = riotLeagueClient.getLeagueEntries("EMERALD", "I");
                if (emeraldEntries != null && !emeraldEntries.isEmpty()) {
                    // 상위 일부만 수집 (너무 많으면 API 할당량 초과 위험)
                    List<TftLeagueEntryDto> limitedEntries = emeraldEntries.subList(0, Math.min(emeraldEntries.size(), 200));
                    collectLeagueEntries(limitedEntries, "EMERALD", 50);
                }
            } catch (Exception e) {
                log.error("Failed to collect Emerald I entries: {}", e.getMessage());
            }
        }

        log.info("High Tier player collection finished.");
    }

    private int collectLeague(TftLeagueListDto league, String tierName, int priority) {
        if (league == null || league.getEntries() == null) {
            log.warn("League data is null or empty for {}", tierName);
            return 0;
        }

        if (!league.getEntries().isEmpty()) {
            log.info("Sample first entry puuid: {}", league.getEntries().get(0).getPuuid());
        }

        int count = 0;
        for (TftLeagueItemDto entry : league.getEntries()) {
            if (processEntry(entry, tierName, priority)) {
                count++;
            }
        }
        log.info("Added {} new players to queue from {}", count, tierName);
        return count;
    }

    /**
     * 리스트 형태(다이아몬드 이하)의 엔트리를 처리합니다.
     */
    private void collectLeagueEntries(List<TftLeagueEntryDto> entries, String tierName, int priority) {
        int count = 0;
        for (TftLeagueEntryDto entry : entries) {
            // TftLeagueEntryDto를 TftLeagueItemDto와 호환되게 변환하거나 직접 처리
            TftLeagueItemDto item = new TftLeagueItemDto();
            item.setSummonerId(entry.getSummonerId());
            item.setLeaguePoints(entry.getLeaguePoints());
            item.setWins(entry.getWins());
            item.setLosses(entry.getLosses());
            item.setRank(entry.getRank());
            // PUUID는 Entries API 결과에 없을 수 있으므로 ID 변환 큐를 타게 됨
            
            if (processEntry(item, tierName, priority)) {
                count++;
            }
        }
        log.info("Added {} players to queue from {} I entries", count, tierName);
    }

    private boolean processEntry(TftLeagueItemDto entry, String tierName, int priority) {
        // Case 1: PUUID가 있는 경우
        if (entry.getPuuid() != null && !entry.getPuuid().isEmpty()) {
            redisQueueService.pushTask(entry.getPuuid(), "SUMMONER", priority);
            try {
                saveLpHistory(entry, tierName);
            } catch (Exception e) {
                log.error("Failed to save LP history for {}: {}", entry.getPuuid(), e.getMessage());
            }
            return true;
        } 
        // Case 2: SummonerID만 있는 경우
        else if (entry.getSummonerId() != null) {
            redisQueueService.pushTask(entry.getSummonerId(), "SUMMONER_ID", priority);
            return true;
        }
        return false;
    }

    private void saveLpHistory(TftLeagueItemDto entry, String tier) {
        com.tft.batch.model.entity.LpHistory lastRecord = lpHistoryRepository.findTopByPuuidOrderByCreatedAtDesc(entry.getPuuid());

        boolean needUpdate = false;
        int iconId = 29; // 기본 아이콘 지정

        if (lastRecord == null) {
            needUpdate = true;
            iconId = fetchIconIdSafely(entry.getPuuid());
        } else {
            // 이미 수집된 아이콘이 있으면 재사용, 없으면 수집
            iconId = lastRecord.getProfileIconId() > 0 ? lastRecord.getProfileIconId() : fetchIconIdSafely(entry.getPuuid());

            // 점수나 티어가 바뀌었을 때만 저장
            if (lastRecord.getLp() != entry.getLeaguePoints() || !lastRecord.getTier().equals(tier)) {
                needUpdate = true;
            }
            // 기존 데이터 마이그레이션: 과거 기록(승패가 0)일 경우 누적 데이터 최신화를 위해 새로 저장
            if (lastRecord.getWins() == 0 && entry.getWins() > 0) {
                needUpdate = true;
            }
            // 아이콘 번호 갱신 목적의 마이그레이션 저장
            if (lastRecord.getProfileIconId() == 0 && iconId > 0) {
                needUpdate = true;
            }
        }

        if (needUpdate) {
            lpHistoryRepository.save(com.tft.batch.model.entity.LpHistory.builder()
                    .puuid(entry.getPuuid())
                    .tier(tier)
                    .rank_str(entry.getRank())
                    .lp(entry.getLeaguePoints())
                    .wins(entry.getWins())
                    .losses(entry.getLosses())
                    .profileIconId(iconId)
                    .createdAt(LocalDateTime.now())
                    .build());
        }
    }

    private int fetchIconIdSafely(String puuid) {
        try {
            // 라이엇 API 제한(2분에 100회=1.2초당 1회)을 매우 안전하게 우회하기 위해 1.5초 대기
            Thread.sleep(1500); 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        com.tft.batch.client.dto.TftSummonerDto summoner = riotLeagueClient.getTftSummonerByPuuid(puuid);
        return (summoner != null && summoner.getProfileIconId() > 0) ? summoner.getProfileIconId() : 29;
    }
}
