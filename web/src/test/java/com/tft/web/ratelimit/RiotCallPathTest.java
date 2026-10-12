package com.tft.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.tft.web.config.CacheConfig;
import com.tft.web.config.RiotRestTemplateConfig;
import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.repository.GameInfoRepository;
import com.tft.web.service.MatchServiceImp;
import com.tft.web.service.RiotSummonerApiClient;

/**
 * 실제 빈 구성(riotRestTemplate + 제한기 인터셉터 + Redis 캐시)으로 Web의 Riot 호출 경로를 검증한다.
 * Riot은 가짜 서버(MockRestServiceServer), Redis는 실제(localhost:6379)를 쓴다.
 */
@SpringJUnitConfig(RiotCallPathTest.TestBeans.class)
@TestPropertySource(properties = "riot.api.key=dummy")
class RiotCallPathTest {

    static final String PREFIX = "test:" + UUID.randomUUID() + ":";

    @Configuration
    @Import({ CacheConfig.class, RiotRestTemplateConfig.class, RiotRateLimitInterceptor.class,
            RiotSummonerApiClient.class })
    static class TestBeans {
        // "24h" 같은 설정값을 Duration으로 읽기 위한 Spring Boot 변환기(전체 부트 컨텍스트 없이 띄우므로 직접 등록)
        @Bean
        static org.springframework.core.convert.ConversionService conversionService() {
            return org.springframework.boot.convert.ApplicationConversionService.getSharedInstance();
        }

        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory("localhost", 6379);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }

        // 운영 키와 섞이지 않게 접두사만 다르게 한 제한기(2분 3회)
        @Bean
        RiotRateLimiter riotRateLimiter(StringRedisTemplate redis) {
            return new RiotRateLimiter(redis, PREFIX, 120_000, 3, 1_000, 100);
        }
    }

    @Autowired
    RestTemplate riotRestTemplate;
    @Autowired
    RiotSummonerApiClient apiClient;
    @Autowired
    StringRedisTemplate redis;

    private MockRestServiceServer riot;
    private String gameName;

    @BeforeEach
    void setUp() {
        boolean redisUp;
        try {
            redisUp = "PONG".equals(redis.getConnectionFactory().getConnection().ping());
        } catch (Exception e) {
            redisUp = false;
        }
        assumeTrue(redisUp, "Redis(localhost:6379)가 실행 중이어야 합니다");
        riot = MockRestServiceServer.bindTo(riotRestTemplate).build();
        gameName = "cachetest" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        if (gameName != null) {
            apiClient.evictAccount(gameName, "KR1");
        }
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private Long recordedCalls() {
        return redis.opsForZSet().zCard(PREFIX + RiotRateLimiter.CALLS_KEY);
    }

    @Test
    void cachedResultDoesNotCallRiotOrSpendBudget() {
        String url = "https://asia.api.riotgames.com/riot/account/v1/accounts/by-riot-id/" + gameName + "/KR1";
        riot.expect(once(), requestTo(url)).andRespond(withSuccess(
                "{\"puuid\":\"p-1\",\"gameName\":\"" + gameName + "\",\"tagLine\":\"KR1\"}", MediaType.APPLICATION_JSON));

        RiotAccountDto first = apiClient.getAccountByRiotId(gameName, "KR1");
        RiotAccountDto second = apiClient.getAccountByRiotId(gameName, "KR1"); // 캐시에서 반환

        assertThat(first.getPuuid()).isEqualTo("p-1");
        assertThat(second.getPuuid()).isEqualTo("p-1");
        riot.verify();                              // Riot 요청은 1번뿐
        assertThat(recordedCalls()).isEqualTo(1);   // 한도도 1번만 사용
    }

    @Test
    void summonerApiCallsAreStoppedOnceBudgetIsSpent() {
        for (int i = 0; i < 3; i++) {
            riot.expect(once(), requestTo("https://kr.api.riotgames.com/tft/league/v1/by-puuid/p-" + i))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        apiClient.getTftLeagueByPuuid("p-0");
        apiClient.getTftLeagueByPuuid("p-1");
        apiClient.getTftLeagueByPuuid("p-2");
        // 4번째: 가짜 서버에 기대 요청이 없으므로 HTTP가 나가면 AssertionError가 난다
        assertThatThrownBy(() -> apiClient.getTftLeagueByPuuid("p-3")).isInstanceOf(RiotRateLimitedException.class);

        riot.verify();
        assertThat(recordedCalls()).isEqualTo(3);
    }

    @Test
    void matchDetailNotInDbGoesThroughTheSameLimiter() {
        // 한도를 먼저 다 쓴다
        for (int i = 0; i < 3; i++) {
            riot.expect(once(), requestTo("https://kr.api.riotgames.com/tft/league/v1/by-puuid/q-" + i))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 3; i++) {
            apiClient.getTftLeagueByPuuid("q-" + i);
        }

        // DB에 없는 매치의 상세 조회: MatchServiceImp도 같은 riotRestTemplate을 쓴다
        MatchServiceImp matchService = new MatchServiceImp();
        GameInfoRepository gameInfoRepository = mock(GameInfoRepository.class);
        when(gameInfoRepository.findByGaId("KR_404")).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(matchService, "restTemplate", riotRestTemplate);
        ReflectionTestUtils.setField(matchService, "gameInfoRepository", gameInfoRepository);
        ReflectionTestUtils.setField(matchService, "apiKey", "dummy");

        assertThatThrownBy(() -> matchService.getMatchDetailById("KR_404", "me"))
                .isInstanceOf(RiotRateLimitedException.class);

        riot.verify();                             // 매치 상세 요청은 Riot으로 나가지 않았다
        assertThat(recordedCalls()).isEqualTo(3);
    }

    @Test
    void everyRiotCallerUsesTheLimitedRestTemplate() {
        // Web에서 Riot을 부르는 두 클래스가 모두 이 빈을 주입받는지(직접 new RestTemplate 하지 않는지) 확인
        assertThat(riotRestTemplate.getInterceptors()).hasSize(1)
                .first().isInstanceOf(RiotRateLimitInterceptor.class);
        assertThat(ReflectionTestUtils.getField(apiClient, "restTemplate")).isSameAs(riotRestTemplate);
        assertThat(List.of(MatchServiceImp.class.getDeclaredFields())).anySatisfy(f -> {
            assertThat(f.getType()).isEqualTo(RestTemplate.class);
            assertThat(f.isAnnotationPresent(Autowired.class)).isTrue();
        });
    }
}
