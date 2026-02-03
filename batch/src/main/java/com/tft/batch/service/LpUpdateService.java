package com.tft.batch.service;

import com.tft.batch.client.RiotLeagueClient;
import com.tft.batch.client.dto.TftLeagueEntryDto;
import com.tft.batch.model.entity.LpHistory;
import com.tft.batch.repository.LpHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LpUpdateService {

    private final LpHistoryRepository lpHistoryRepository;
    private final RiotLeagueClient riotLeagueClient;

    @Transactional
    public void updateActiveSummonersLp() {
        // 1. Get PUUIDs active in the last 24 hours
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        List<String> activePuuids = lpHistoryRepository.findDistinctPuuidByCreatedAtAfter(oneDayAgo);

        log.info("Found {} active summoners to track LP.", activePuuids.size());

        for (String puuid : activePuuids) {
            try {
                processPuuid(puuid);
                // Thread.sleep removed for faster processing
            } catch (Exception e) {
                log.error("Error updating LP for puuid: {}", puuid, e);
            }
        }
    }

    private void processPuuid(String puuid) {
        // API 호출
        TftLeagueEntryDto league = riotLeagueClient.getTftLeagueByPuuid(puuid);
        if (league == null) return;

        LpHistory lastRecord = lpHistoryRepository.findTopByPuuidOrderByCreatedAtDesc(puuid);

        boolean needUpdate = false;
        // DB 기록 없는 유저(신규 유저)인 경우 바로 저장
        if (lastRecord == null) {
            needUpdate = true;
        }
        else {
            // 이전 점수와 현재 점수 비교해서 변했을 때만 저장.
            if (lastRecord.getLp() != league.getLeaguePoints() || !lastRecord.getTier().equals(league.getTier())) {
                needUpdate = true;
            }
        }

        // 저장용 객체 만들어서 DB에 추가
        if (needUpdate) {
            LpHistory newHistory = LpHistory.builder()
                    .puuid(puuid)
                    .tier(league.getTier()) //CHALLENGER, ..., IRON
                    .rank_str(league.getRank()) //I~IV
                    .lp(league.getLeaguePoints()) // 0~
                    .createdAt(LocalDateTime.now())
                    .build();
            
            lpHistoryRepository.save(newHistory);
            log.info("Updated LP for {}: {} {} {}LP", puuid, league.getTier(), league.getRank(), league.getLeaguePoints());
        }
    }
}
