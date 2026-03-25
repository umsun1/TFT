package com.tft.batch.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tft.batch.client.dto.TftLeagueEntryDto;
import com.tft.batch.client.dto.TftLeagueListDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class RiotLeagueClient {

    private final RestTemplate restTemplate;

    @Value("${riot.api.key}")
    private String apiKey;

    public TftLeagueListDto getChallengerLeague() {
        String url = "https://kr.api.riotgames.com/tft/league/v1/challenger?api_key=" + apiKey;
        log.info("Calling Riot API: {}", url.replace(apiKey, "REDACTED"));
        try {
            String rawJson = restTemplate.getForObject(url, String.class);
            log.info("Raw JSON Response (first 500 chars): {}", rawJson != null ? rawJson.substring(0, Math.min(rawJson.length(), 500)) : "null");
            return new ObjectMapper().readValue(rawJson, TftLeagueListDto.class);
        } catch (Exception e) {
            log.error("Error parsing Challenger League: {}", e.getMessage());
            return null;
        }
    }

    public TftLeagueListDto getGrandmasterLeague() {
        String url = "https://kr.api.riotgames.com/tft/league/v1/grandmaster?api_key=" + apiKey;
        log.info("Calling Riot API: {}", url.replace(apiKey, "REDACTED"));
        try {
            String rawJson = restTemplate.getForObject(url, String.class);
            return new ObjectMapper().readValue(rawJson, TftLeagueListDto.class);
        } catch (Exception e) {
            log.error("Error parsing Grandmaster League: {}", e.getMessage());
            return null;
        }
    }

    public TftLeagueListDto getMasterLeague() {
        String url = "https://kr.api.riotgames.com/tft/league/v1/master?api_key=" + apiKey;
        log.info("Calling Riot API: {}", url.replace(apiKey, "REDACTED"));
        try {
            String rawJson = restTemplate.getForObject(url, String.class);
            return new ObjectMapper().readValue(rawJson, TftLeagueListDto.class);
        } catch (Exception e) {
            log.error("Error parsing Master League: {}", e.getMessage());
            return null;
        }
    }

    public TftLeagueEntryDto getTftLeagueByPuuid(String puuid) {
        String url = "https://kr.api.riotgames.com/tft/league/v1/by-puuid/" + puuid + "?api_key=" + apiKey;

        try {
            ResponseEntity<List<TftLeagueEntryDto>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            null,
                            new ParameterizedTypeReference<List<TftLeagueEntryDto>>() {}
                    );

            List<TftLeagueEntryDto> results = response.getBody();
            // Assuming the first entry is the relevant ranked queue or logic to filter by queue type if necessary
            // For now, consistent with web implementation which takes the first one.
            return (results != null && !results.isEmpty()) ? results.get(0) : null;
        } catch (Exception e) {
            // Log error or handle gracefully (e.g. 404 if unranked)
            return null;
        }
    }

    public com.tft.batch.client.dto.TftSummonerDto getTftSummonerByPuuid(String puuid) {
        String url = "https://kr.api.riotgames.com/tft/summoner/v1/summoners/by-puuid/" + puuid + "?api_key=" + apiKey;
        try {
            return restTemplate.getForObject(url, com.tft.batch.client.dto.TftSummonerDto.class);
        } catch (Exception e) {
            log.error("Error fetching summoner for {}: {}", puuid, e.getMessage());
            return null;
        }
    }
}
