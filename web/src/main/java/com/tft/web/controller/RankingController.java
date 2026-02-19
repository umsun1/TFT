package com.tft.web.controller;

import com.tft.web.domain.LpHistory;
import com.tft.web.domain.Participant;
import com.tft.web.model.dto.ParticipantSimpleDto;
import com.tft.web.model.dto.RankingDto;
import com.tft.web.repository.LpHistoryRepository;
import com.tft.web.repository.ParticipantRepository;
import com.tft.web.service.TftStaticDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class RankingController {

    private final LpHistoryRepository lpHistoryRepository;
    private final ParticipantRepository participantRepository;
    private final TftStaticDataService staticDataService;

    @GetMapping("/ranking")
    public String ranking(Model model) {
        // 1. 랭킹 데이터 조회 (최대 100명)
        List<LpHistory> histories = lpHistoryRepository.findTopRankers();
        
        if (histories.isEmpty()) {
            model.addAttribute("rankingList", new ArrayList<>());
            return "tft/ranking";
        }

        // 2. PUUID 목록 추출
        List<String> puuids = histories.stream()
                .map(LpHistory::getPuuid)
                .collect(java.util.stream.Collectors.toList());

        // 3. 소환사 정보 일괄 조회 (N+1 문제 해결)
        List<ParticipantSimpleDto> participants = participantRepository.findLatestParticipantsByPuuids(puuids);
        
        // 4. 조회 속도를 위해 Map으로 변환
        java.util.Map<String, ParticipantSimpleDto> pMap = participants.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ParticipantSimpleDto::getPaPuuid, 
                        p -> p, 
                        (p1, p2) -> p1 // 중복 발생 시 첫 번째 것 사용 (혹시 모를 대비)
                ));

        List<RankingDto> rankingList = new ArrayList<>();
        int rank = 1;

        for (LpHistory h : histories) {
            String name = "Unknown";
            String tag = "KR1";
            String iconUrl = staticDataService.getTacticianImgUrl(1); // 기본 아이콘

            // Map에서 정보 조회
            if (pMap.containsKey(h.getPuuid())) {
                ParticipantSimpleDto p = pMap.get(h.getPuuid());
                name = p.getPaName();
                tag = p.getPaTag();
                if (p.getPaCompanionId() != null) {
                    iconUrl = staticDataService.getTacticianImgUrl(p.getPaCompanionId());
                }
            }

            rankingList.add(RankingDto.builder()
                    .rank(rank++)
                    .summonerName(name)
                    .tagLine(tag)
                    .tier(h.getTier())
                    .lp(h.getLp())
                    .profileIconUrl(iconUrl)
                    .build());
        }

        model.addAttribute("rankingList", rankingList);
        return "tft/ranking";
    }
}
