package com.tft.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;

import com.tft.web.ratelimit.RiotRateLimitedException;
import com.tft.web.repository.ParticipantRepository;
import com.tft.web.service.MatchService;
import com.tft.web.service.SummonerService;

/** 검색이 실패했을 때 원인별로 다른 안내가 나가는지 검증한다. */
class MainControllerTest {

    private SummonerService summonerService;
    private MatchService matchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        summonerService = mock(SummonerService.class);
        matchService = mock(MatchService.class);
        MainController controller = new MainController();
        ReflectionTestUtils.setField(controller, "summonerService", summonerService);
        ReflectionTestUtils.setField(controller, "matchService", matchService);
        ReflectionTestUtils.setField(controller, "participantRepository", mock(ParticipantRepository.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rateLimitedSearchTellsUserWhenToRetry() throws Exception {
        when(summonerService.getSummonerData(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RiotRateLimitedException(41_200, false, null));

        mockMvc.perform(get("/summoner/kr/someone/KR1"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("errorMessage", containsString("약 42초 후")));
    }

    @Test
    void limiterOutageDoesNotPromiseASpecificTime() throws Exception {
        when(summonerService.getSummonerData(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RiotRateLimitedException(5_000, true, new IllegalStateException("redis down")));

        mockMvc.perform(get("/summoner/kr/someone/KR1"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("errorMessage", containsString("일시적인 문제")))
                .andExpect(flash().attribute("errorMessage", not(containsString("초 후"))));
    }

    @Test
    void unknownSummonerIsNotReportedAsRateLimit() throws Exception {
        when(summonerService.getSummonerData(anyString(), anyString(), anyString(), any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", new HttpHeaders(), null, null));

        mockMvc.perform(get("/summoner/kr/nobody/KR1"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("errorMessage", "소환사를 찾을 수 없습니다."));
    }

    @Test
    void rateLimitedMatchDetailIs503NotNotFound() throws Exception {
        when(matchService.getMatchDetailById(anyString(), anyString()))
                .thenThrow(new RiotRateLimitedException(3_000, false, null));

        mockMvc.perform(get("/match/KR_1/detail")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void missingMatchDetailIsStill404() throws Exception {
        when(matchService.getMatchDetailById(anyString(), anyString())).thenReturn(null);

        mockMvc.perform(get("/match/KR_1/detail")).andExpect(status().isNotFound());
    }
}
