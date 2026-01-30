package com.tft.web.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.tft.web.domain.Participant;
import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.model.dto.SummonerDto;
import com.tft.web.model.dto.SummonerProfileDto;
import com.tft.web.model.dto.TftLeagueEntryDto;
import com.tft.web.repository.ParticipantRepository;

@Service
public  class SummonerServiceImp implements SummonerService{

    @Value("${riot.api.key}")
    private String apiKey;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private com.tft.web.repository.MatchFetchQueueRepository queueRepository;

    @Autowired
    private com.tft.web.repository.LpHistoryRepository lpHistoryRepository;

    @Autowired
    private TftStaticDataService tftStaticDataService;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public SummonerProfileDto getSummonerData(String server, String gameName, String tagLine, Integer queueId) {
        // 1. Riot ID → Account (서버 임시 고정)
        RiotAccountDto account = getAccountByRiotId(gameName, tagLine);
        if (account == null) return null;
        String puuid = account.getPuuid();

        // [수정] 배치를 통한 데이터 수집 요청 (새로운 전적 확인을 위해 상태 갱신)
        java.util.Optional<com.tft.web.domain.MatchFetchQueue> existingQueue = queueRepository.findByMfqIdAndMfqType(puuid, "SUMMONER");
        if (existingQueue.isPresent()) {
            com.tft.web.domain.MatchFetchQueue queue = existingQueue.get();
            if (!"FETCHING".equals(queue.getMfqStatus())) {
                queue.setMfqStatus("READY");
                queue.setMfqPriority(999);
                queueRepository.save(queue);
            }
        } else {
            queueRepository.save(com.tft.web.domain.MatchFetchQueue.builder()
                    .mfqId(puuid)
                    .mfqType("SUMMONER")
                    .mfqStatus("READY")
                    .mfqPriority(999)
                    .build());
        }

        // 2. PUUID → TFT League (배열로 오기 때문에 첫 번째 요소 추출)
        TftLeagueEntryDto league = getTftLeagueByPuuid(puuid);

        // 3. PUUID → Summoner (레벨, 아이콘 정보)
        SummonerDto summoner = getTftSummonerByPuuid(puuid);

        // 4. 데이터 조립 (Null 체크 포함)
        SummonerProfileDto profile = new SummonerProfileDto();
        profile.setSummonerName(account.getGameName());
        profile.setTagLine(account.getTagLine());
        profile.setPuuid(puuid);

        if (league != null) {
            // [추가] LP 변화 기록 (마지막 기록과 다를 때만 저장)
            com.tft.web.domain.LpHistory lastRecord = lpHistoryRepository.findTopByPuuidOrderByCreatedAtDesc(puuid);
            if (lastRecord == null || lastRecord.getLp() != league.getLeaguePoints() || !lastRecord.getTier().equals(league.getTier())) {
                lpHistoryRepository.save(com.tft.web.domain.LpHistory.builder()
                        .puuid(puuid)
                        .tier(league.getTier())
                        .rank_str(league.getRank())
                        .lp(league.getLeaguePoints())
                        .build());
            }

            // 1. 전체 통계 계산 (DB 기반)
            List<Participant> allMatches = participantRepository.findByPaPuuid(puuid);

            // [추가] 큐 ID 필터링 (전체인 경우 queueId가 null이거나 0일 수 있음)
            if (queueId != null && queueId != 0) {
                allMatches = allMatches.stream()
                    .filter(p -> p.getGameInfo().getQueueId() != null && p.getGameInfo().getQueueId().equals(queueId))
                    .collect(java.util.stream.Collectors.toList());
            }
            
            if (!allMatches.isEmpty()) {
                double totalPlacement = allMatches.stream().mapToInt(Participant::getPaPlacement).sum();
                long wins = allMatches.stream().filter(p -> p.getPaPlacement() == 1).count();
                long top4 = allMatches.stream().filter(p -> p.getPaPlacement() <= 4).count();
                
                profile.setAvgPlacement(totalPlacement / allMatches.size());
                profile.setWinRate((double) wins / allMatches.size() * 100.0);
                profile.setTop4Rate((double) top4 / allMatches.size());
                profile.setWinCount(wins);
            }

            // 2. 최근 20게임 상세 통계 (차트용)
            List<Participant> recentMatches = allMatches.stream()
                    .sorted((p1, p2) -> p2.getGameInfo().getGaDatetime().compareTo(p1.getGameInfo().getGaDatetime()))
                    .limit(20)
                    .collect(java.util.stream.Collectors.toList());

            if (!recentMatches.isEmpty()) {
                int[] counts = new int[8];
                double recentTotalPlacement = 0;
                int recentTop4 = 0;
                int recentWins = 0;

                for (Participant p : recentMatches) {
                    int place = p.getPaPlacement();
                    if (place >= 1 && place <= 8) counts[place - 1]++;
                    recentTotalPlacement += place;
                    if (place <= 4) recentTop4++;
                    if (place == 1) recentWins++;
                }

                profile.setRankCounts(counts);
                profile.setRecentAvgPlacement(recentTotalPlacement / recentMatches.size());
                profile.setRecentTop4Rate((double) recentTop4 / recentMatches.size() * 100.0);
                profile.setRecentWinRate((double) recentWins / recentMatches.size() * 100.0);
                profile.setRecentPlacements(recentMatches.stream()
                        .map(com.tft.web.domain.Participant::getPaPlacement)
                        .collect(java.util.stream.Collectors.toList()));

                // [추가/수정] 업적 계산 로직 강화
                List<String> achievements = new java.util.ArrayList<>();
                
                // 1. 연승 중 (최근 3게임 연속 Top 4)
                if (recentMatches.size() >= 3) {
                    boolean isWinningStreak = true;
                    for (int i = 0; i < 3; i++) {
                        if (recentMatches.get(i).getPaPlacement() > 4) {
                            isWinningStreak = false;
                            break;
                        }
                    }
                    if (isWinningStreak) achievements.add("🔥 연승 중");
                }

                // 2. 순방의 신 (Top 4 확률 75% 이상)
                if (profile.getRecentTop4Rate() >= 75.0) achievements.add("📈 순방의 신");

                // 3. 1등 수집가 (1등 4회 이상)
                if (recentWins >= 4) achievements.add("👑 1등 수집가");

                // 4. 리롤 장인 (평균 3성 유닛 2.0개 이상)
                double avg3Stars = recentMatches.stream()
                    .mapToDouble(p -> p.getUnits().stream().filter(u -> u.getUnTier() == 3).count())
                    .average().orElse(0);
                if (avg3Stars >= 2.0) achievements.add("✨ 리롤 장인");

                // 5. 고밸류 지향 (평균 4, 5코스트 유닛 4개 이상)
                double avgHighValue = recentMatches.stream()
                    .mapToDouble(p -> p.getUnits().stream().filter(u -> u.getUnCost() >= 4).count())
                    .average().orElse(0);
                if (avgHighValue >= 4.0) achievements.add("💎 고밸류 지향");

                // 6. 시너지 술사 (평균 활성 시너지 7개 이상)
                double avgTraits = recentMatches.stream()
                    .mapToDouble(p -> p.getTraits().size())
                    .average().orElse(0);
                if (avgTraits >= 7.0) achievements.add("🔮 시너지의 신");

                // 7. 레벨업 광신도 (9레벨 도달율 30% 이상)
                long level9Count = recentMatches.stream().filter(p -> p.getPaLevel() >= 9).count();
                if ((double) level9Count / recentMatches.size() >= 0.3) achievements.add("🚀 후전드");

                // 8. 시너지 애호가 (한글화 적용)
                Map<String, Long> traitCounts = recentMatches.stream()
                    .flatMap(p -> p.getTraits().stream())
                    .collect(java.util.stream.Collectors.groupingBy(com.tft.web.domain.Trait::getTrName, java.util.stream.Collectors.counting()));
                
                traitCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(entry -> {
                        if (entry.getValue() >= 6) { // 20판 중 6번 이상
                            String koTraitName = tftStaticDataService.getTraitKoName(entry.getKey());
                            achievements.add("#" + koTraitName + " 애호가");
                        }
                    });

                profile.setAchievements(achievements);
            }

            // [추가] LP 히스토리 조회 (최근 15개)
            List<com.tft.web.domain.LpHistory> historyList = lpHistoryRepository.findTop15ByPuuidOrderByCreatedAtDesc(puuid);
            java.util.Collections.reverse(historyList); // 시간순 정렬
            
            // [수정] 단순 LP가 아닌 "티어 보정 점수"로 변환하여 그래프가 승급을 반영하도록 수정
            profile.setLpHistory(historyList.stream()
                .map(h -> convertTierToTotalLp(h.getTier(), h.getRank_str(), h.getLp()))
                .collect(java.util.stream.Collectors.toList()));
            
            // [추가] 툴팁용 실제 LP 저장
            profile.setRealLpHistory(historyList.stream()
                .map(com.tft.web.domain.LpHistory::getLp)
                .collect(java.util.stream.Collectors.toList()));
                
            profile.setLpHistoryLabels(historyList.stream()
                .map(h -> h.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd")))
                .collect(java.util.stream.Collectors.toList()));
            profile.setLpHistoryTiers(historyList.stream()
                .map(h -> h.getTier() + " " + h.getRank_str())
                .collect(java.util.stream.Collectors.toList()));

            profile.setTier(league.getTier());
            profile.setRank(league.getRank());
            profile.setLp(league.getLeaguePoints());
            profile.setWins(league.getWins());
            profile.setLosses(league.getLosses());
            
            profile.setCollectedCount(allMatches.size());
            profile.setTotalCount(league.getWins() + league.getLosses());
            profile.setFetching(profile.getCollectedCount() < profile.getTotalCount());

        } else {
            // 리그 정보가 없는 경우 (언랭크)
            profile.setTier("UNRANKED");
            profile.setRank("");
        }
        if (summoner != null) {
            profile.setProfileIconId(summoner.getProfileIconId());
            profile.setSummonerLevel(summoner.getSummonerLevel());
        }
        return profile;
    }

    private int convertTierToTotalLp(String tier, String rank, int lp) {
        if (tier == null) return 0;
        
        int baseScore = 0;
        switch (tier) {
            case "IRON":        baseScore = 0; break;
            case "BRONZE":      baseScore = 400; break;
            case "SILVER":      baseScore = 800; break;
            case "GOLD":        baseScore = 1200; break;
            case "PLATINUM":    baseScore = 1600; break;
            case "EMERALD":     baseScore = 2000; break;
            case "DIAMOND":     baseScore = 2400; break;
            case "MASTER":      baseScore = 2800; break;
            case "GRANDMASTER": baseScore = 2800; break; // 마스터 이상은 LP로만 구분
            case "CHALLENGER":  baseScore = 2800; break;
            default:            baseScore = 0;
        }

        // 마스터 이상은 랭크(I, II, III, IV) 개념이 없음
        if (baseScore >= 2800) {
            return baseScore + lp;
        }

        // 랭크 보정 (IV=0, III=100, II=200, I=300)
        int rankScore = 0;
        if (rank != null) {
            switch (rank) {
                case "IV": rankScore = 0; break;
                case "III": rankScore = 100; break;
                case "II": rankScore = 200; break;
                case "I": rankScore = 300; break;
            }
        }

        return baseScore + rankScore + lp;
    }

    public RiotAccountDto getAccountByRiotId(String gameName, String tagLine) {
        String url = "https://asia.api.riotgames.com/riot/account/v1/accounts/by-riot-id/"+ gameName + "/" + tagLine;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Riot-Token", apiKey);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<RiotAccountDto> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        RiotAccountDto.class
                );
                System.out.println(response.getBody());
        return response.getBody();
    }

    public TftLeagueEntryDto getTftLeagueByPuuid(String puuid) {
        String url = "https://kr.api.riotgames.com/tft/league/v1/by-puuid/" + puuid;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Riot-Token", apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // List<TftLeagueEntryDto> 형태로 받아야 합니다.
        ResponseEntity<List<TftLeagueEntryDto>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        new org.springframework.core.ParameterizedTypeReference<List<TftLeagueEntryDto>>() {}
                );

        List<TftLeagueEntryDto> results = response.getBody();
        System.out.println("ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ");
        System.out.println(results);
        System.out.println("ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ");
        // 배열 중 첫 번째 요소(보통 랭크 정보)를 꺼내서 리턴
        return (results != null && !results.isEmpty()) ? results.get(0) : null;
    }

    public SummonerDto getTftSummonerByPuuid(String puuid){
        String url = "https://kr.api.riotgames.com/tft/summoner/v1/summoners/by-puuid/" + puuid;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Riot-Token", apiKey);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<SummonerDto> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        SummonerDto.class
                );
                System.out.println(response.getBody());
        return response.getBody();
    }

    public double getAveragePlacement(String puuid) {
        // 1. DB에서 이 유저의 시즌 16 참가 기록만 가져옴
        List<Participant> seasonMatches = participantRepository.findByPaPuuidAndGameInfo_GaDatetimeAfter(puuid, LocalDateTime.of(2025, 12, 3, 0, 0)); // 시즌 시작일 기준)

        if (seasonMatches.isEmpty()) return 0.0;

        // 2. 등수 합산 및 평균 계산
        int totalRank = seasonMatches.stream()
                .mapToInt(Participant::getPaPlacement)
                .sum();

        return (double) totalRank / seasonMatches.size();
    }

    public Map<String, Object> getWinStatistics(String puuid) {
        // 1. 시즌 전체 매치 기록 조회
        List<Participant> seasonMatches = participantRepository.findByPaPuuid(puuid); // 시즌 필터 포함된 쿼리 권장

        if (seasonMatches.isEmpty()) {
            return Map.of("winCount", 0L, "winRate", 0.0);
        }

        // 2. 1등 횟수 계산
        long winCount = seasonMatches.stream()
                .filter(p -> p.getPaPlacement() == 1)
                .count();

        // 3. 승률 계산 (1등 횟수 / 전체 판수 * 100)
        double winRate = (double) winCount / seasonMatches.size() * 100.0;

        return Map.of("winCount", winCount, "winRate", winRate);
    }

    
    
}