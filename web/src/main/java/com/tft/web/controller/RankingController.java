package com.tft.web.controller;

import com.tft.web.domain.LpHistory;
import com.tft.web.domain.Participant;
import com.tft.web.model.dto.ParticipantSimpleDto;
import com.tft.web.model.dto.RankingDto;
import com.tft.web.repository.LpHistoryRepository;
import com.tft.web.repository.ParticipantRepository;
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
    // private final TftStaticDataService staticDataService;

    @GetMapping("/ranking")
    public String ranking(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
            Model model) {
        int pageSize = 100;

        // 1. 랭킹 데이터 조회 (최대 300명)
        List<LpHistory> allHistories = lpHistoryRepository.findTopRankers();

        int totalElements = allHistories.size();
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);

        if (totalPages == 0)
            totalPages = 1;
        if (page < 1)
            page = 1;
        if (page > totalPages)
            page = totalPages;

        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, totalElements);

        List<LpHistory> histories = new ArrayList<>();
        if (fromIndex < totalElements) {
            histories = allHistories.subList(fromIndex, toIndex);
        }

        if (histories.isEmpty()) {
            model.addAttribute("rankingList", new ArrayList<>());
            model.addAttribute("currentPage", 1);
            model.addAttribute("totalPages", 1);
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
        int rank = fromIndex + 1;

        for (LpHistory h : histories) {
            String name = "Unknown";
            String tag = "KR1";
            // DB에 수집된 프로필 아이콘이 있으면 사용, 없으면 여전히 29 (기본값)
            int iconId = h.getProfileIconId() > 0 ? h.getProfileIconId() : 29;
            String iconUrl = "https://ddragon.leagueoflegends.com/cdn/16.8.1/img/profileicon/" + iconId + ".png";

            // Map에서 소환사명 등 정보 조회
            if (pMap.containsKey(h.getPuuid())) {
                ParticipantSimpleDto p = pMap.get(h.getPuuid());
                name = p.getPaName();
                tag = p.getPaTag();
                // [요청 반영] 전설이(Companion) 아이콘 대신 소환사 아이콘 노출
                // if (p.getPaCompanionId() != null) {
                // iconUrl = staticDataService.getTacticianImgUrl(p.getPaCompanionId());
                // }
            }

            int wins = h.getWins();
            int losses = h.getLosses();
            double winRate = 0.0;
            if (wins + losses > 0) {
                winRate = (double) wins / (wins + losses) * 100.0;
            }

            rankingList.add(RankingDto.builder()
                    .rank(rank++)
                    .summonerName(name)
                    .tagLine(tag)
                    .tier(h.getTier())
                    .rankStr(h.getRank_str())
                    .lp(h.getLp())
                    .wins(wins)
                    .losses(losses)
                    .winRate(winRate)
                    .profileIconUrl(iconUrl)
                    .build());
        }

        // 최근 업데이트 시간 추출
        String lastUpdated = "업데이트 대기중";
        if (!allHistories.isEmpty() && allHistories.get(0).getCreatedAt() != null) {
            java.time.LocalDateTime createdAt = allHistories.get(0).getCreatedAt();
            lastUpdated = createdAt.format(java.time.format.DateTimeFormatter.ofPattern("MM월 dd일 HH:mm 기준"));
        }

        model.addAttribute("rankingList", rankingList);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("lastUpdated", lastUpdated);
        return "tft/ranking";
    }
}
