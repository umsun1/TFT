package com.tft.web.model.dto;

import lombok.Data;

@Data
public class SummonerProfileDto {
    private String summonerName;
    private String tagLine;
    private String puuid;
    
    private String tier;
    private String rank;
    private int lp;
    
    private int wins;
    private int losses;
    
    private int profileIconId;
    private long summonerLevel;

    private long winCount; // 1등 횟수
    private double avgPlacement;
    private double top4Rate;
    private double winRate;

    // [중앙 상단용: 최근 20게임 상세 통계] - 새로 추가할 것들
    private double recentAvgPlacement;
    private double recentTop4Rate;
    private double recentWinRate;
    private int[] rankCounts; // [1등횟수, 2등횟수, ..., 8등횟수] -> 차트용

    // [추가] 데이터 수집 상태
    private boolean isFetching;
    private int collectedCount;
    private int totalCount; // 이건 리그 정보 등에서 가져오거나 큐 정보에서 추산

    // [추가] LP 변화 추이 데이터
    private java.util.List<Integer> lpHistory;     // 그래프 Y축용 (환산 점수)
    private java.util.List<Integer> realLpHistory; // 툴팁 표시용 (실제 LP)
    private java.util.List<String> lpHistoryLabels;
    private java.util.List<String> lpHistoryTiers;

    // [추가] 업적 태그
    private java.util.List<String> achievements;

    // [추가] 최근 20게임 등수 리스트
    private java.util.List<Integer> recentPlacements;

    // [추가] 티어 날개 또는 테두리 URL
    private String tierWingsUrl;
}
