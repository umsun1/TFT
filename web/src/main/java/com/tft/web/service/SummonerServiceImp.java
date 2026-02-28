package com.tft.web.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.tft.web.domain.LpHistory;
import com.tft.web.domain.MatchFetchQueue;
import com.tft.web.domain.Participant;
import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.model.dto.SummonerDto;
import com.tft.web.model.dto.SummonerProfileDto;
import com.tft.web.model.dto.SummonerStatsDto;
import com.tft.web.model.dto.TftLeagueEntryDto;
import com.tft.web.repository.LpHistoryRepository;
import com.tft.web.repository.MatchFetchQueueRepository;
import com.tft.web.repository.ParticipantRepository;

@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public  class SummonerServiceImp implements SummonerService{

    @Value("${riot.api.key}")
    private String apiKey;

    // [추가] 단순 메모리 캐시 (API 호출 최소화)
    private final Map<String, RiotAccountDto> accountCache = new ConcurrentHashMap<>();
    private final Map<String, TftLeagueEntryDto> leagueCache = new ConcurrentHashMap<>();

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private MatchFetchQueueRepository queueRepository;

    @Autowired
    private LpHistoryRepository lpHistoryRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    @org.springframework.transaction.annotation.Transactional
    public SummonerProfileDto getSummonerData(String server, String gameName, String tagLine, Integer queueId) {
        String cacheKey = gameName + "#" + tagLine;
        
        // 1. Riot ID → Account (캐시 확인)
        RiotAccountDto account = accountCache.get(cacheKey);
        if (account == null) {
            account = getAccountByRiotId(gameName, tagLine);
            if (account == null) return null;
            accountCache.put(cacheKey, account);
        }
        String puuid = account.getPuuid();

        // [병렬 실행 시작] 리그 정보와 소환사 정보를 동시에 요청
        CompletableFuture<TftLeagueEntryDto> leagueFuture = 
            CompletableFuture.supplyAsync(() -> {
                TftLeagueEntryDto cached = leagueCache.get(puuid);
                if (cached != null) return cached;
                TftLeagueEntryDto league = getTftLeagueByPuuid(puuid);
                if (league != null) leagueCache.put(puuid, league);
                return league;
            });
        
        CompletableFuture<SummonerDto> summonerFuture = 
            CompletableFuture.supplyAsync(() -> getTftSummonerByPuuid(puuid));

        // 큐 등록 (상태 갱신만 수행, DB 쓰기 발생)
        updateFetchQueue(puuid);

        TftLeagueEntryDto league = leagueFuture.join();
        SummonerDto summoner = summonerFuture.join();

        // 4. 데이터 조립
        SummonerProfileDto profile = new SummonerProfileDto();
        profile.setSummonerName(account.getGameName());
        profile.setTagLine(account.getTagLine());
        profile.setPuuid(puuid);

        if (league != null) {
            // LP 변화 기록 - 비동기로 처리하여 응답 속도 향상 권장되나 일단 직접 실행
            saveLpHistory(puuid, league);

            // 1. 전체 통계 계산 (DB 기반 - SQL 집계)
            SummonerStatsDto stats = participantRepository.getSummonerStats(puuid, queueId == null ? 0 : queueId);
            
            if (stats != null && stats.getTotalCount() > 0) {
                profile.setAvgPlacement(stats.getAvgPlacement());
                profile.setWinRate((double) stats.getWinCount() / stats.getTotalCount() * 100.0);
                profile.setTop4Rate((double) stats.getTop4Count() / stats.getTotalCount());
                profile.setWinCount(stats.getWinCount());
            }

            // 2. 최근 20게임 상세 통계 (필요한 데이터만 조회)
            List<Participant> recentMatches = participantRepository.findRecentMatchesByPuuid(
                puuid, 
                queueId == null ? 0 : queueId, 
                PageRequest.of(0, 20)
            );

            if (!recentMatches.isEmpty()) {
                calculateAchievements(profile, recentMatches);
            }

            profile.setTier(league.getTier());
            profile.setRank(league.getRank());
            profile.setLp(league.getLeaguePoints());
            profile.setWins(league.getWins());
            profile.setLosses(league.getLosses());
            
            profile.setCollectedCount(stats != null ? stats.getTotalCount().intValue() : 0);
            profile.setTotalCount(league.getWins() + league.getLosses());
            profile.setFetching(profile.getCollectedCount() < profile.getTotalCount());
        } else {
            profile.setTier("UNRANKED");
            profile.setRank("");
        }
        
        if (summoner != null) {
            profile.setProfileIconId(summoner.getProfileIconId());
            profile.setSummonerLevel(summoner.getSummonerLevel());
        }
        
        // LP 히스토리 그래프 데이터 구성 (최적화: 필요한 필드만 조회 권장)
        loadLpHistoryData(profile, puuid);

        return profile;
    }

    private void updateFetchQueue(String puuid) {
        Optional<MatchFetchQueue> existingQueue = queueRepository.findByMfqIdAndMfqType(puuid, "SUMMONER");
        if (existingQueue.isPresent()) {
            MatchFetchQueue queue = existingQueue.get();
            if (!"FETCHING".equals(queue.getMfqStatus())) {
                queue.setMfqStatus("READY");
                queue.setMfqPriority(999);
                queueRepository.save(queue);
            }
        } else {
            queueRepository.save(MatchFetchQueue.builder()
                    .mfqId(puuid)
                    .mfqType("SUMMONER")
                    .mfqStatus("READY")
                    .mfqPriority(999)
                    .build());
        }
    }

    private void saveLpHistory(String puuid, TftLeagueEntryDto league) {
        LpHistory lastRecord = lpHistoryRepository.findTopByPuuidOrderByCreatedAtDesc(puuid);
        if (lastRecord == null || lastRecord.getLp() != league.getLeaguePoints() || !lastRecord.getTier().equals(league.getTier())) {
            lpHistoryRepository.save(LpHistory.builder()
                    .puuid(puuid)
                    .tier(league.getTier())
                    .rank_str(league.getRank())
                    .lp(league.getLeaguePoints())
                    .build());
        }
    }

    private void calculateAchievements(SummonerProfileDto profile, List<Participant> recentMatches) {
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
                .map(Participant::getPaPlacement)
                .collect(Collectors.toList()));

        List<String> achievements = new ArrayList<>();
        
        // 간단한 업적부터 계산
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
        if (profile.getRecentTop4Rate() >= 75.0) achievements.add("📈 순방의 신");
        if (recentWins >= 4) achievements.add("👑 1등 수집가");

        // 유닛/시너지 기반 업적은 필요한 경우에만 계산 (Lazy 로딩 최소화)
        double avg3Stars = recentMatches.stream()
            .mapToDouble(p -> p.getUnits().stream().filter(u -> u.getUnTier() == 3).count())
            .average().orElse(0);
        if (avg3Stars >= 2.0) achievements.add("✨ 리롤 장인");

        double avgHighValue = recentMatches.stream()
            .mapToDouble(p -> p.getUnits().stream().filter(u -> u.getUnCost() >= 4).count())
            .average().orElse(0);
        if (avgHighValue >= 4.0) achievements.add("💎 고밸류 지향");

        long level9Count = recentMatches.stream().filter(p -> p.getPaLevel() >= 9).count();
        if ((double) level9Count / recentMatches.size() >= 0.3) achievements.add("🚀 후전드");

        profile.setAchievements(achievements);
    }

    private void loadLpHistoryData(SummonerProfileDto profile, String puuid) {
        List<LpHistory> historyList = lpHistoryRepository.findTop15ByPuuidOrderByCreatedAtDesc(puuid);
        Collections.reverse(historyList);
        
        profile.setLpHistory(historyList.stream()
            .map(h -> convertTierToTotalLp(h.getTier(), h.getRank_str(), h.getLp()))
            .collect(Collectors.toList()));
        profile.setRealLpHistory(historyList.stream()
            .map(LpHistory::getLp)
            .collect(Collectors.toList()));
        profile.setLpHistoryLabels(historyList.stream()
            .map(h -> h.getCreatedAt().format(DateTimeFormatter.ofPattern("MM-dd")))
            .collect(Collectors.toList()));
        profile.setLpHistoryTiers(historyList.stream()
            .map(h -> h.getTier() + " " + h.getRank_str())
            .collect(Collectors.toList()));
    }

    // 그래프용 티어 점수 계산기
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

    // 닉네임 + 태그라인 -> PUUID
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

    // PUUID -> TFT 관련 프로필
    public TftLeagueEntryDto getTftLeagueByPuuid(String puuid) {
        String url = "https://kr.api.riotgames.com/tft/league/v1/by-puuid/" + puuid;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Riot-Token", apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // List<TftLeagueEntryDto> 형태로 받아야 합니다.

        try {
            ResponseEntity<List<TftLeagueEntryDto>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        new ParameterizedTypeReference<List<TftLeagueEntryDto>>() {}
                );

            List<TftLeagueEntryDto> results = response.getBody();
            if (results == null || results.isEmpty()) return null;

            // RANKED_TFT (솔로 랭크)를 우선적으로 찾음
            return results.stream()
                .filter(league -> "RANKED_TFT".equals(league.getQueueType()))
                .findFirst()
                .orElse(results.get(0)); // 솔랭이 없으면 첫 번째 정보라도 반환
        }  catch (HttpClientErrorException.TooManyRequests e) {
            String retryAfter = e.getResponseHeaders().getFirst("Retry-After");
            System.err.println("매치 상세 조회 실패: "+ retryAfter+"후에 다시 시도 하시오.");
            e.printStackTrace();
            return null;
        }
    }

    // PUUID -> 소환사 레벨, 아이콘
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

    // 플레이어 평균 등수 계산
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

    // 플레이어 승률(1등 확률) 계산
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