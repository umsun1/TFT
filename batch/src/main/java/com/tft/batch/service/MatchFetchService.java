package com.tft.batch.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import com.tft.batch.client.RiotMatchClient;
import com.tft.batch.client.RiotSummonerClient;
import com.tft.batch.client.dto.RiotMatchDetailResponse;
import com.tft.batch.client.dto.TftSummonerDto;
import com.tft.batch.repository.GameInfoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchFetchService {

    private final RedisQueueService redisQueueService;
    private final RiotMatchClient riotMatchClient;
    private final RiotSummonerClient riotSummonerClient;
    private final MatchDetailSaveService matchDetailSaveService;
    private final GameInfoRepository gameInfoRepository;
    
    // [추가] 랭킹 정보 즉시 갱신을 위한 의존성
    private final com.tft.batch.client.RiotLeagueClient riotLeagueClient;
    private final com.tft.batch.repository.LpHistoryRepository lpHistoryRepository;

    @Transactional
    public void fetchNext() {
        // Redis 큐에서 우선순위가 가장 높은 작업 하나를 꺼냅니다.
        RedisQueueService.QueueTask task = redisQueueService.popTask();
        if (task == null) return;

        processTask(task);
    }

    private void processTask(RedisQueueService.QueueTask queue) {
        try {
            if ("SUMMONER".equals(queue.type)) {
                processSummoner(queue);
            } else if ("MATCH".equals(queue.type)) {
                processMatch(queue);
            } else if ("SUMMONER_ID".equals(queue.type)) {
                processSummonerId(queue);
            }
            // ZSet에서 이미 Pop 되었으므로 DONE 처리는 별도로 필요하지 않습니다.
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Rate limit exceeded. Waiting based on Retry-After header...");
            
            String retryAfter = e.getResponseHeaders().getFirst("Retry-After");
            int waitSec = (retryAfter != null) ? Integer.parseInt(retryAfter) : 10;
            
            // 상태를 READY 대신 Redis 큐에 다시 밀어넣어 다음 순서에 시도하도록 합니다. (Re-queue)
            redisQueueService.pushTask(queue.id, queue.type, queue.priority);

            try {
                Thread.sleep((waitSec + 1) * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            log.error("Error processing queue {}: {}", queue.id, e.getMessage());
            // 에러 발생 시 버려지거나 데드레터 큐로 보냄 (현재는 로그만 남김)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSummonerId(RedisQueueService.QueueTask queue) {
        log.info("Resolving SummonerID={} to PUUID", queue.id);
        TftSummonerDto summoner = riotSummonerClient.getSummonerById(queue.id);
        if (summoner != null && summoner.getPuuid() != null) {
            String puuid = summoner.getPuuid();
            
            // 1. 매치 수집 큐 등록 (Redis ZSet 활용)
            redisQueueService.pushTask(puuid, "SUMMONER", queue.priority);

            // 2. [추가] 랭킹 정보(LpHistory) 즉시 갱신
            // (SummonerID로 수집된 유저는 LpHistory가 없으므로 여기서 채워줘야 랭킹에 뜸)
            try {
                com.tft.batch.client.dto.TftLeagueEntryDto league = riotLeagueClient.getTftLeagueByPuuid(puuid);
                if (league != null) {
                    com.tft.batch.model.entity.LpHistory lastRecord = lpHistoryRepository.findTopByPuuidOrderByCreatedAtDesc(puuid);
                    boolean needUpdate = (lastRecord == null) 
                            || (lastRecord.getLp() != league.getLeaguePoints()) 
                            || (!lastRecord.getTier().equals(league.getTier()));

                    if (needUpdate) {
                        lpHistoryRepository.save(com.tft.batch.model.entity.LpHistory.builder()
                                .puuid(puuid)
                                .tier(league.getTier())
                                .rank_str(league.getRank())
                                .lp(league.getLeaguePoints())
                                .createdAt(java.time.LocalDateTime.now())
                                .build());
                        log.info("Initialized LpHistory for resolved PUUID: {}", puuid);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to fetch/save League info for resolved PUUID {}: {}", puuid, e.getMessage());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSummoner(RedisQueueService.QueueTask queue) {
        log.info("Fetching ALL match IDs for PUUID={} since Season Start (Dec 3)", queue.id);
        
        java.util.List<String> allMatchIds = new java.util.ArrayList<>();
        int start = 0;
        long seasonStartEpoch = 1764687600L; // 2025년 12월 3일 기준

        // 1. Riot API에서 매치 ID 리스트 수집
        while (true) {
            java.util.List<String> ids = riotMatchClient.fetchMatchIds(queue.id, start, 100, seasonStartEpoch);
            if (ids == null || ids.isEmpty()) break;
            allMatchIds.addAll(ids);
            if (ids.size() < 100) break;
            start += 100;
        }
        
        log.info("Total matches found for {}: {}", queue.id, allMatchIds.size());
        
        if (allMatchIds.isEmpty()) return;

        // 2. [Bulk Optimization] 이미 저장된 게임 조회
        java.util.List<String> existingGaIds = gameInfoRepository.findExistingGaIds(allMatchIds);
        java.util.Set<String> finishedGameSet = new java.util.HashSet<>(existingGaIds);

        int newPriority = (int) queue.priority - 1;
        int pushedCount = 0;

        // 3. 필터링 후 Redis 큐로 푸시
        for (String matchId : allMatchIds) {
            // 이미 수집된 게임이면 스킵
            if (finishedGameSet.contains(matchId)) {
                continue;
            }
            redisQueueService.pushTask(matchId, "MATCH", newPriority);
            pushedCount++;
        }

        if (pushedCount > 0) {
            log.info("Queued {} matches for fetching.", pushedCount);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processMatch(RedisQueueService.QueueTask queue) {
        log.info("Fetching details for MatchID={}", queue.id);
        RiotMatchDetailResponse response = riotMatchClient.fetchMatchDetail(queue.id);
        if (response != null) {
            matchDetailSaveService.save(response);
        }
    }
}