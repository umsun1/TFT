package com.tft.web.service;

import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.model.dto.SummonerProfileDto;

public interface SummonerService {
    SummonerProfileDto getSummonerData(String server, String gameName, String tagLine, Integer queueId);

    public RiotAccountDto getAccountByRiotId(String gameName, String tagLine);

}
