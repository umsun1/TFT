package com.tft.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.model.dto.SummonerDto;
import com.tft.web.model.dto.SummonerProfileDto;
import com.tft.web.model.dto.SummonerStatsDto;
import com.tft.web.model.dto.TftLeagueEntryDto;
import com.tft.web.ratelimit.RiotRateLimitedException;
import com.tft.web.repository.LpHistoryRepository;
import com.tft.web.repository.ParticipantRepository;

/** 리그 조회 결과에 따라 프로필이 어떻게 만들어지는지 검증한다. (Riot·DB·Redis는 모두 가짜) */
class SummonerServiceImpTest {

    private static final String PUUID = "puuid-1";

    private RiotSummonerApiClient riotApiClient;
    private ParticipantRepository participantRepository;
    private RedisQueueService redisQueueService;
    private LpHistoryRepository lpHistoryRepository;
    private SummonerServiceImp service;

    @BeforeEach
    void setUp() {
        riotApiClient = mock(RiotSummonerApiClient.class);
        participantRepository = mock(ParticipantRepository.class);
        redisQueueService = mock(RedisQueueService.class);
        lpHistoryRepository = mock(LpHistoryRepository.class);
        service = new SummonerServiceImp();
        ReflectionTestUtils.setField(service, "riotApiClient", riotApiClient);
        ReflectionTestUtils.setField(service, "participantRepository", participantRepository);
        ReflectionTestUtils.setField(service, "redisQueueService", redisQueueService);
        ReflectionTestUtils.setField(service, "lpHistoryRepository", lpHistoryRepository);

        RiotAccountDto account = new RiotAccountDto();
        account.setGameName("테스터");
        account.setTagLine("KR1");
        account.setPuuid(PUUID);
        when(riotApiClient.getAccountByRiotId("테스터", "KR1")).thenReturn(account);

        SummonerDto summoner = new SummonerDto();
        summoner.setProfileIconId(7);
        summoner.setSummonerLevel(123);
        when(riotApiClient.getTftSummonerByPuuid(PUUID)).thenReturn(summoner);

        // DB에 저장된 전적 40경기: 평균 3.5등, 1등 8번, 4등 이내 24번
        SummonerStatsDto stats = mock(SummonerStatsDto.class);
        when(stats.getTotalCount()).thenReturn(40L);
        when(stats.getAvgPlacement()).thenReturn(3.5);
        when(stats.getWinCount()).thenReturn(8L);
        when(stats.getTop4Count()).thenReturn(24L);
        when(participantRepository.getSummonerStats(PUUID, 1100)).thenReturn(stats);
        when(participantRepository.findRecentMatchesByPuuid(anyString(), anyInt(), any())).thenReturn(List.of());
        when(lpHistoryRepository.findTop15ByPuuidOrderByCreatedAtDesc(PUUID)).thenReturn(new ArrayList<>());
    }

    private SummonerProfileDto search() {
        return service.getSummonerData("kr", "테스터", "KR1", 1100);
    }

    private void assertStoredStatsAreShown(SummonerProfileDto profile) {
        assertThat(profile.getAvgPlacement()).isEqualTo(3.5);
        assertThat(profile.getWinRate()).isEqualTo(20.0);
        assertThat(profile.getTop4Rate()).isEqualTo(0.6);
        assertThat(profile.getWinCount()).isEqualTo(8);
        assertThat(profile.getCollectedCount()).isEqualTo(40);
    }

    @Test
    void rankedPlayerShowsTierAndStats() {
        TftLeagueEntryDto league = new TftLeagueEntryDto();
        league.setQueueType("RANKED_TFT");
        league.setTier("DIAMOND");
        league.setRank("II");
        league.setLeaguePoints(55);
        league.setWins(30);
        league.setLosses(70);
        when(riotApiClient.getTftLeagueByPuuid(PUUID)).thenReturn(league);

        SummonerProfileDto profile = search();

        assertThat(profile.getLeagueStatus()).isEqualTo("OK");
        assertThat(profile.getTier()).isEqualTo("DIAMOND");
        assertThat(profile.getLp()).isEqualTo(55);
        assertThat(profile.getTotalCount()).isEqualTo(100);
        assertThat(profile.isFetching()).isTrue();
        assertStoredStatsAreShown(profile);
        verify(lpHistoryRepository).save(any());
        verify(redisQueueService).pushTask(PUUID, "SUMMONER", 999);
    }

    @Test
    void playerWithoutRankRecordIsUnranked() {
        when(riotApiClient.getTftLeagueByPuuid(PUUID)).thenReturn(null); // 조회 성공, 랭크 기록 없음

        SummonerProfileDto profile = search();

        assertThat(profile.getLeagueStatus()).isEqualTo("OK");
        assertThat(profile.isLeagueAvailable()).isTrue();
        assertThat(profile.getTier()).isEqualTo("UNRANKED");
        assertStoredStatsAreShown(profile);
    }

    @Test
    void rateLimitedLeagueLookupIsNotShownAsUnranked() {
        when(riotApiClient.getTftLeagueByPuuid(PUUID)).thenThrow(new RiotRateLimitedException(30_000, false, null));

        SummonerProfileDto profile = search();

        assertThat(profile.getLeagueStatus()).isEqualTo("RATE_LIMITED");
        assertThat(profile.isLeagueAvailable()).isFalse();
        assertThat(profile.getTier()).isNotEqualTo("UNRANKED");
        assertThat(profile.isFetching()).isFalse();
        assertStoredStatsAreShown(profile);            // 통계는 리그 조회와 무관하게 계산된다
        assertThat(profile.getProfileIconId()).isEqualTo(7);
        verify(lpHistoryRepository, never()).save(any()); // 모르는 값을 LP 기록으로 남기지 않는다
    }

    @Test
    void riot429OnLeagueLookupIsRateLimitedToo() {
        when(riotApiClient.getTftLeagueByPuuid(PUUID)).thenThrow(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "", new HttpHeaders(), null, null));

        SummonerProfileDto profile = search();

        assertThat(profile.getLeagueStatus()).isEqualTo("RATE_LIMITED");
        assertThat(profile.getTier()).isNotEqualTo("UNRANKED");
        assertStoredStatsAreShown(profile);
    }

    @Test
    void limiterUnavailableOrServerErrorIsUnavailable() {
        when(riotApiClient.getTftLeagueByPuuid(PUUID)).thenThrow(
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "", new HttpHeaders(), null, null));

        SummonerProfileDto profile = search();

        assertThat(profile.getLeagueStatus()).isEqualTo("UNAVAILABLE");
        assertThat(profile.getTier()).isNotEqualTo("UNRANKED");
        assertStoredStatsAreShown(profile);
    }

    @Test
    void failedSummonerLookupDoesNotFailThePage() {
        when(riotApiClient.getTftLeagueByPuuid(PUUID)).thenReturn(null);
        when(riotApiClient.getTftSummonerByPuuid(PUUID)).thenThrow(new RiotRateLimitedException(5_000, false, null));

        SummonerProfileDto profile = search();

        assertThat(profile.getTier()).isEqualTo("UNRANKED");
        assertThat(profile.getProfileIconId()).isZero();
        assertStoredStatsAreShown(profile);
    }

    @Test
    void invalidPuuidStillStopsSearchAndClearsAccountCache() {
        when(riotApiClient.getTftLeagueByPuuid(PUUID)).thenThrow(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "", new HttpHeaders(), null, null));

        assertThatThrownBy(this::search).isInstanceOf(HttpClientErrorException.BadRequest.class);

        verify(riotApiClient).evictAccount("테스터", "KR1");
    }

    @Test
    void rateLimitedAccountLookupStopsSearchWithoutQueueing() {
        when(riotApiClient.getAccountByRiotId("처음", "KR1")).thenThrow(new RiotRateLimitedException(12_000, false, null));

        assertThatThrownBy(() -> service.getSummonerData("kr", "처음", "KR1", 1100))
                .isInstanceOf(RiotRateLimitedException.class);

        verify(redisQueueService, never()).pushTask(anyString(), anyString(), anyDouble());
    }
}
