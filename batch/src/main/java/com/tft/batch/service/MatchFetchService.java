package com.tft.batch.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
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

        while (true) {
            // startTime 파라미터를 추가하여 시즌 시작일 이후 데이터만 타겟팅
            java.util.List<String> ids = riotMatchClient.fetchMatchIds(queue.getMfqId(), start, 100, seasonStartEpoch);
            
            if (ids == null || ids.isEmpty()) break;
            allMatchIds.addAll(ids);
            
            if (ids.size() < 100) break; // 100개 미만이면 마지막 페이지
            start += 100;
        }
        
        log.info("Total matches found for {}: {}", queue.getMfqId(), allMatchIds.size());
        
        for (String matchId : allMatchIds) {
            // 이미 수집된 게임이면 스킵
            if (gameInfoRepository.existsByGaId(matchId)) {
                continue;
            }

            // 큐에 이미 존재하는지 확인 (중복 방지 및 재시도 로직)
            java.util.Optional<MatchFetchQueue> existingQueue = queueRepository.findByMfqId(matchId);
            
            if (existingQueue.isPresent()) {
                MatchFetchQueue q = existingQueue.get();
                // 이전에 실패했다면 다시 시도하도록 상태 리셋
                if ("FAIL".equals(q.getMfqStatus())) {
                    q.markReady(); // 상태를 READY로 변경
                    q.setMfqUpdatedAt(java.time.LocalDateTime.now());
                    queueRepository.save(q);
                    log.info("Reset FAIL status to READY for MatchID={}", matchId);
                }
                // READY나 FETCHING 상태면 아무것도 안 함 (중복 적재 방지)
            } else {
                // 큐에도 없고 DB에도 없으면 신규 등록
                queueRepository.save(MatchFetchQueue.builder()
                        .mfqId(matchId)
                        .mfqType("MATCH")
                        .mfqStatus("READY")
                        .mfqPriority(queue.getMfqPriority() - 1)
                        .mfqUpdatedAt(java.time.LocalDateTime.now())
                        .build());
            }
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