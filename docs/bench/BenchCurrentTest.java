package com.tft.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.model.dto.SummonerDto;
import com.tft.web.model.dto.TftLeagueEntryDto;
import com.tft.web.service.RedisQueueService;
import com.tft.web.service.RiotSummonerApiClient;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

// 측정 전용(현재 코드용): Riot API 클라이언트와 대기열 등록만 가짜로 바꾸고
// 컨트롤러~DB~템플릿 렌더링은 실제 코드로 실행한다.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/tft_db?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Seoul",
        "spring.datasource.username=root",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "riot.api.key=dummy"
})
@AutoConfigureMockMvc
@Transactional
class BenchCurrentTest {

    @Autowired MockMvc mockMvc;
    @Autowired EntityManagerFactory emf;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RiotSummonerApiClient riotApiClient;
    @MockitoBean RedisQueueService redisQueueService;

    @Test
    void bench() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        int warm = Integer.getInteger("bench.warm", 5);
        int runs = Integer.getInteger("bench.runs", 15);
        int idx = 0;

        for (String puuid : System.getProperty("bench.puuids").split(",")) {
            idx++;
            RiotAccountDto account = new RiotAccountDto();
            account.setGameName("bench");
            account.setTagLine("KR1");
            account.setPuuid(puuid);
            when(riotApiClient.getAccountByRiotId(anyString(), anyString())).thenReturn(account);

            // LP 기록 INSERT가 측정에 섞이지 않도록 마지막 기록과 같은 값을 돌려준다
            TftLeagueEntryDto league = new TftLeagueEntryDto();
            league.setQueueType("RANKED_TFT");
            league.setRank("I");
            league.setWins(100);
            league.setLosses(100);
            List<Map<String, Object>> last = jdbc.queryForList(
                    "select tier, lp from lp_history where puuid = ? order by created_at desc limit 1", puuid);
            league.setTier(last.isEmpty() ? "MASTER" : (String) last.get(0).get("tier"));
            league.setLeaguePoints(last.isEmpty() ? 0 : ((Number) last.get(0).get("lp")).intValue());
            when(riotApiClient.getTftLeagueByPuuid(anyString())).thenReturn(league);

            SummonerDto summoner = new SummonerDto();
            summoner.setProfileIconId(1);
            summoner.setSummonerLevel(100);
            when(riotApiClient.getTftSummonerByPuuid(anyString())).thenReturn(summoner);

            int total = jdbc.queryForObject("select count(*) from participant where pa_puuid = ?", Integer.class, puuid);

            String url = "/summoner/kr/bench" + idx + "/KR1";
            long[] ms = new long[runs];
            long stmts = 0, entities = 0;
            int status = 0, bytes = 0;
            for (int i = 0; i < warm + runs; i++) {
                em.flush();
                em.clear();
                stats.clear();
                long t0 = System.nanoTime();
                MvcResult r = mockMvc.perform(get(url)).andReturn();
                long t1 = System.nanoTime();
                if (i >= warm) ms[i - warm] = (t1 - t0) / 1_000_000;
                stmts = stats.getPrepareStatementCount();
                entities = stats.getEntityLoadCount();
                status = r.getResponse().getStatus();
                bytes = r.getResponse().getContentAsByteArray().length;
                if (r.getResolvedException() != null) throw new RuntimeException(r.getResolvedException());
            }
            long[] sorted = ms.clone();
            Arrays.sort(sorted);
            System.out.println("BENCH puuid=" + puuid.substring(0, 8) + " matches=" + total
                    + " status=" + status + " htmlBytes=" + bytes
                    + " sql=" + stmts + " entitiesLoaded=" + entities
                    + " medianMs=" + sorted[runs / 2] + " minMs=" + sorted[0] + " maxMs=" + sorted[runs - 1]);
        }
    }
}
