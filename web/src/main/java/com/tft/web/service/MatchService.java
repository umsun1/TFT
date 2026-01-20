package com.tft.web.service;

import java.util.List;

import org.springframework.data.domain.Page;

import com.tft.web.domain.Participant;
import com.tft.web.model.dto.MatchApiDto;


public interface MatchService {
    List<String> getMatchIds(String puuid);

    List<MatchApiDto> getMatchDetail(List<String> matchIds, String puuid);

    public Page<MatchApiDto> getRecentMatches(String puuid, int page, Integer queueId);
}
