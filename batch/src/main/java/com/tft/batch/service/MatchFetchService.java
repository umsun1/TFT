package com.tft.batch.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import com.tft.batch.client.RiotMatchClient;
import com.tft.batch.client.RiotSummonerClient;
import com.tft.batch.client.dto.RiotMatchDetailResponse;
import com.tft.batch.client.dto.TftSummonerDto;
import com.tft.batch.model.entity.MatchFetchQueue;
import com.tft.batch.repository.GameInfoRepository;
import com.tft.batch.repository.MatchFetchQueueRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchFetchService {

    private final MatchFetchQueueRepository queueRepository;
    private final RiotMatchClient riotMatchClient;
    private final RiotSummonerClient riotSummonerClient;
    private final MatchDetailSaveService matchDetailSaveService;
    private final GameInfoRepository gameInfoRepository;
    
    // [추가] 랭킹 정보 즉시 갱신을 위한 의존성
    private final com.tft.batch.client.RiotLeagueClient riotLeagueClient;
    private final com.tft.batch.repository.LpHistoryRepository lpHistoryRepository;

    @Transactional
    public void fetchNext() {
        // 개발자 키의 한계를 고려하여 한 번에 1개씩만 처리
        int pickedCount = queueRepository.pickNext();
        if (pickedCount == 0) return;

        java.util.List<MatchFetchQueue> tasks = queueRepository.findFetchingAll();
        if (tasks.isEmpty()) return;

        // 순차 처리로 변경 (Dev Key 안정성 확보)
        for (MatchFetchQueue queue : tasks) {
            processTask(queue);
        }
    }

    private void processTask(MatchFetchQueue queue) {
        try {
            if ("SUMMONER".equals(queue.getMfqType())) {
                processSummoner(queue);
            } else if ("MATCH".equals(queue.getMfqType())) {
                processMatch(queue);
            } else if ("SUMMONER_ID".equals(queue.getMfqType())) {
                processSummonerId(queue);
            }
            updateStatus(queue.getMfqNum(), "DONE");
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Rate limit exceeded. Waiting based on Retry-After header...");
            
            String retryAfter = e.getResponseHeaders().getFirst("Retry-After");
            int waitSec = (retryAfter != null) ? Integer.parseInt(retryAfter) : 10;
            
            // 상태를 READY로 되돌림 (나중에 다시 시도)
            updateStatus(queue.getMfqNum(), "READY");

            try {
                Thread.sleep((waitSec + 1) * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            log.error("Error processing queue {}: {}", queue.getMfqId(), e.getMessage());
            updateStatus(queue.getMfqNum(), "FAIL");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(Long mfqNum, String status) {
        queueRepository.findById(mfqNum).ifPresent(q -> {
            if ("DONE".equals(status)) q.markDone();
            else if ("FAIL".equals(status)) q.markFail();
            else if ("READY".equals(status)) q.markReady();
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSummonerId(MatchFetchQueue queue) {
        log.info("Resolving SummonerID={} to PUUID", queue.getMfqId());
        TftSummonerDto summoner = riotSummonerClient.getSummonerById(queue.getMfqId());
        if (summoner != null && summoner.getPuuid() != null) {
            String puuid = summoner.getPuuid();
            
            // 1. 매치 수집 큐 등록
            if (!queueRepository.existsByMfqId(puuid)) {
                queueRepository.save(MatchFetchQueue.builder()
                        .mfqId(puuid)
                        .mfqType("SUMMONER")
                        .mfqStatus("READY")
                        .mfqPriority(queue.getMfqPriority())
                        .mfqUpdatedAt(java.time.LocalDateTime.now())
                        .build());
            }

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
    public void processSummoner(MatchFetchQueue queue) {
        log.info("Fetching ALL match IDs for PUUID={} since Season Start (Dec 3)", queue.getMfqId());
        
        java.util.List<String> allMatchIds = new java.util.ArrayList<>();
        int start = 0;
        long seasonStartEpoch = 1764687600L; // 2025년 12월 3일 기준

        // 1. Riot API에서 매치 ID 리스트 수집
        while (true) {
            java.util.List<String> ids = riotMatchClient.fetchMatchIds(queue.getMfqId(), start, 100, seasonStartEpoch);
            if (ids == null || ids.isEmpty()) break;
            allMatchIds.addAll(ids);
            if (ids.size() < 100) break;
            start += 100;
        }
        
        log.info("Total matches found for {}: {}", queue.getMfqId(), allMatchIds.size());
        
        if (allMatchIds.isEmpty()) return;

        // 2. [Bulk Optimization] DB 조회를 한 번에 수행
        // 이미 저장된 게임(GameInfo) 조회
        java.util.List<String> existingGaIds = gameInfoRepository.findExistingGaIds(allMatchIds);
        java.util.Set<String> finishedGameSet = new java.util.HashSet<>(existingGaIds);

        // 큐에 있는 상태(MatchFetchQueue) 조회
        java.util.List<MatchFetchQueue> existingQueues = queueRepository.findAllByMfqIdIn(allMatchIds);
        java.util.Map<String, MatchFetchQueue> queueMap = existingQueues.stream()
                .collect(java.util.stream.Collectors.toMap(MatchFetchQueue::getMfqId, q -> q, (q1, q2) -> q1));

        java.util.List<MatchFetchQueue> toSave = new java.util.ArrayList<>();
        int newPriority = queue.getMfqPriority() - 1;

        // 3. 메모리 상에서 필터링 및 로직 처리
        for (String matchId : allMatchIds) {
            // 이미 수집된 게임이면 스킵
            if (finishedGameSet.contains(matchId)) {
                continue;
            }

            if (queueMap.containsKey(matchId)) {
                // 이미 큐에 있는 경우: 상태/우선순위 업데이트
                MatchFetchQueue q = queueMap.get(matchId);
                boolean changed = false;

                if (q.getMfqPriority() < newPriority) {
                    q.setMfqPriority(newPriority);
                    changed = true;
                }
                if ("FAIL".equals(q.getMfqStatus())) {
                    q.markReady();
                    q.setMfqUpdatedAt(java.time.LocalDateTime.now());
                    changed = true;
                    log.info("Reset FAIL status to READY for MatchID={}", matchId);
                }
                if (changed) {
                    toSave.add(q);
                }
            } else {
                // 신규 등록
                toSave.add(MatchFetchQueue.builder()
                        .mfqId(matchId)
                        .mfqType("MATCH")
                        .mfqStatus("READY")
                        .mfqPriority(newPriority)
                        .mfqUpdatedAt(java.time.LocalDateTime.now())
                        .build());
            }
        }

        // 4. [Bulk Insert] 변경사항 한 번에 저장
        if (!toSave.isEmpty()) {
            queueRepository.saveAll(toSave);
            log.info("Queued {} matches for fetching.", toSave.size());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processMatch(MatchFetchQueue queue) {
        log.info("Fetching details for MatchID={}", queue.getMfqId());
        RiotMatchDetailResponse response = riotMatchClient.fetchMatchDetail(queue.getMfqId());
        if (response != null) {
            matchDetailSaveService.save(response);
        }
    }
}