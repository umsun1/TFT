package com.tft.batch.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import com.tft.batch.client.RiotMatchClient;
import com.tft.batch.client.RiotSummonerClient;
import com.tft.batch.client.dto.RiotMatchDetailResponse;
import com.tft.batch.client.dto.TftSummonerDto;
import com.tft.batch.ratelimit.RiotRateLimitedException;
import com.tft.batch.ratelimit.RiotRateLimiter;
import com.tft.batch.repository.GameInfoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchFetchService {

    // 일시적 오류(5xx, 네트워크)로 같은 작업을 다시 시도하는 최대 횟수
    static final int MAX_RETRIES = 3;

    private final RedisQueueService redisQueueService;
    private final RiotMatchClient riotMatchClient;
    private final RiotSummonerClient riotSummonerClient;
    private final MatchDetailSaveService matchDetailSaveService;
    private final GameInfoRepository gameInfoRepository;
    private final RiotRateLimiter rateLimiter;
    
    // [추가] 랭킹 정보 즉시 갱신을 위한 의존성
    private final com.tft.batch.client.RiotLeagueClient riotLeagueClient;
    private final com.tft.batch.repository.LpHistoryRepository lpHistoryRepository;

    // 트랜잭션은 저장 단계(MatchDetailSaveService)에만 둔다. 여기서 열면 외부 API 호출 동안 DB 커넥션을 잡고 있게 된다.
    public void fetchNext() {
        // 한도가 찼거나 429로 차단된 동안에는 작업을 꺼내지 않고 이번 주기를 건너뜁니다.
        long waitMs = rateLimiter.peekWaitMillis();
        if (waitMs > 0) {
            log.debug("Riot rate limit budget exhausted. Skip this tick ({} ms left)", waitMs);
            return;
        }

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
            redisQueueService.clearRetry(queue);
        } catch (HttpClientErrorException.TooManyRequests e) {
            // 차단 시간은 인터셉터가 이미 기록했습니다. 작업만 같은 우선순위로 되돌리고 스레드는 바로 반환합니다.
            redisQueueService.pushTask(queue.id, queue.type, queue.priority);
        } catch (RiotRateLimitedException e) {
            // 작업 도중 한도가 찬 경우(매치 목록을 여러 번 조회하는 작업 등): 되돌려 두고 다음 주기에 다시 시도합니다.
            log.info("Riot rate limit budget exhausted while processing {}. Re-queued.", queue.id);
            redisQueueService.pushTask(queue.id, queue.type, queue.priority);
        } catch (HttpClientErrorException e) {
            // 404(없는 매치) 같은 4xx는 다시 해도 결과가 같으므로 재시도하지 않고 실패 목록에 보관합니다.
            log.error("Error processing queue {}: {}", queue.id, e.getMessage());
            redisQueueService.markFailed(queue, e.getStatusCode().toString());
        } catch (Exception e) {
            // 5xx, 네트워크 오류 등 일시적일 수 있는 오류: 횟수를 세며 재등록하고, 한도를 넘으면 실패 목록에 보관합니다.
            long attempt = redisQueueService.incrementRetry(queue);
            if (attempt <= MAX_RETRIES) {
                log.warn("Error processing queue {} (attempt {}/{}): {}", queue.id, attempt, MAX_RETRIES, e.getMessage());
                redisQueueService.pushTask(queue.id, queue.type, queue.priority);
            } else {
                log.error("Error processing queue {}: {}. Giving up after {} retries.", queue.id, e.getMessage(), MAX_RETRIES);
                redisQueueService.markFailed(queue, String.valueOf(e.getMessage()));
            }
        }
    }

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

    public void processSummoner(RedisQueueService.QueueTask queue) {
        log.info("Fetching ALL match IDs for PUUID={} since Season Start (Apr 15)", queue.id);
        
        java.util.List<String> allMatchIds = new java.util.ArrayList<>();
        int start = 0;
        long seasonStartEpoch = 1776178800L; // 2026년 4월 15일 기준

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

    public void processMatch(RedisQueueService.QueueTask queue) {
        log.info("Fetching details for MatchID={}", queue.id);
        RiotMatchDetailResponse response = riotMatchClient.fetchMatchDetail(queue.id);
        if (response != null) {
            matchDetailSaveService.save(response);
        }
    }
}