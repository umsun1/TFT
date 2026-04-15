package com.tft.batch.client.dto;

import lombok.Data;

@Data
public class TftLeagueEntryDto {
    private String queueType;
    private String tier;
    private String rank;
    private int wins;
    private int losses;
    private int leaguePoints;
    private String summonerId;
    private String summonerName;
}
