package com.tft.web.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RankingDto {
    private int rank;           // 순위
    private String summonerName;// 소환사명
    private String tagLine;     // 태그라인
    private String tier;        // 티어 (CHALLENGER, GRANDMASTER 등)
    private String rankStr;     // 티어 내 등급 (I, II, III, IV)
    private int lp;             // 점수
    private int wins;           // 승리 (1등 횟수가 아닌 Top4 또는 순수 1등? Riot API의 wins는 1등 횟수)
    private int losses;         // 패배 (Top4 미만)
    private double winRate;     // 승률
    private String profileIconUrl; // 프로필 아이콘
}
