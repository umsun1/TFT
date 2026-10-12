package com.tft.web.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.tft.web.config.CacheNames;
import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.model.dto.SummonerDto;
import com.tft.web.model.dto.TftLeagueEntryDto;

/**
 * 소환사 관련 Riot API 호출 + Redis 캐시.
 *
 * SummonerServiceImp 안에 @Cacheable 을 두면 같은 클래스 내부 호출(self-invocation)이라
 * Spring 프록시를 거치지 않아 캐시가 동작하지 않는다. 그래서 별도 빈으로 분리했다.
 */
@Component
public class RiotSummonerApiClient {

    /** Riot ID 캐시 키: 앞뒤 공백 제거 + 소문자 (예: "hide on bush#kr1") */
    private static final String ACCOUNT_KEY = "#gameName.trim().toLowerCase() + '#' + #tagLine.trim().toLowerCase()";

    @Value("${riot.api.key}")
    private String apiKey;

    @org.springframework.beans.factory.annotation.Autowired
    private RestTemplate restTemplate;

    // 닉네임 + 태그라인 -> PUUID (TTL 24h)
    @Cacheable(cacheNames = CacheNames.ACCOUNT, key = ACCOUNT_KEY, unless = "#result == null")
    public RiotAccountDto getAccountByRiotId(String gameName, String tagLine) {
        String url = "https://asia.api.riotgames.com/riot/account/v1/accounts/by-riot-id/" + gameName + "/" + tagLine;
        ResponseEntity<RiotAccountDto> response = restTemplate.exchange(
                url, HttpMethod.GET, authEntity(), RiotAccountDto.class);
        return response.getBody();
    }

    // PUUID -> 티어/LP (TTL 2m)
    // 반환값 null 은 "조회에 성공했고 랭크 기록이 없음(UNRANKED)" 한 가지만 뜻한다.
    // 429·호출 제한·그 외 오류는 예외로 올려 보내 호출한 쪽이 UNRANKED 와 구분하게 한다. (null 은 캐싱하지 않음)
    @Cacheable(cacheNames = CacheNames.LEAGUE, key = "#puuid", unless = "#result == null")
    public TftLeagueEntryDto getTftLeagueByPuuid(String puuid) {
        String url = "https://kr.api.riotgames.com/tft/league/v1/by-puuid/" + puuid;
        ResponseEntity<List<TftLeagueEntryDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, authEntity(),
                new ParameterizedTypeReference<List<TftLeagueEntryDto>>() {
                });

        List<TftLeagueEntryDto> results = response.getBody();
        if (results == null || results.isEmpty())
            return null;

        // RANKED_TFT (솔로 랭크)를 우선적으로 찾음
        return results.stream()
                .filter(league -> "RANKED_TFT".equals(league.getQueueType()))
                .findFirst()
                .orElse(results.get(0)); // 솔랭이 없으면 첫 번째 정보라도 반환
    }

    // PUUID -> 소환사 레벨, 아이콘 (TTL 10m)
    @Cacheable(cacheNames = CacheNames.SUMMONER, key = "#puuid", unless = "#result == null")
    public SummonerDto getTftSummonerByPuuid(String puuid) {
        String url = "https://kr.api.riotgames.com/tft/summoner/v1/summoners/by-puuid/" + puuid;
        ResponseEntity<SummonerDto> response = restTemplate.exchange(
                url, HttpMethod.GET, authEntity(), SummonerDto.class);
        return response.getBody();
    }

    /** Riot ID -> puuid 매핑 캐시 제거 (puuid 가 더 이상 유효하지 않을 때 등) */
    @CacheEvict(cacheNames = CacheNames.ACCOUNT, key = ACCOUNT_KEY)
    public void evictAccount(String gameName, String tagLine) {
    }

    /** 티어/LP + 레벨/아이콘 캐시 제거 (추후 "전적 갱신" 버튼 등에서 사용) */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEAGUE, key = "#puuid"),
            @CacheEvict(cacheNames = CacheNames.SUMMONER, key = "#puuid")
    })
    public void evictSummonerInfo(String puuid) {
    }

    private HttpEntity<Void> authEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Riot-Token", apiKey);
        return new HttpEntity<>(headers);
    }
}
