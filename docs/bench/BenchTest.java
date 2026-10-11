package com.tft.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.model.dto.SummonerDto;
import com.tft.web.model.dto.TftLeagueEntryDto;
import com.tft.web.repository.MatchFetchQueueRepository;
import com.tft.web.service.MatchService;
import com.tft.web.service.SummonerService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

// 측정 전용: Riot API 응답만 고정값으로 대체하고 컨트롤러~DB~템플릿 렌더링은 실제 코드로 실행한다.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/tft_db?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Seoul",
        "spring.datasource.username=root",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "riot.api.key=dummy"
})
@AutoConfigureMockMvc
@Transactional
class BenchTest {

    @Autowired MockMvc mockMvc;
    @Autowired EntityManagerFactory emf;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired SummonerService summonerService;
    @Autowired MatchService matchService;
    @MockitoBean MatchFetchQueueRepository queueRepository;

    static class FakeRiot extends RestTemplate {
        volatile String puuid;
        volatile List<String> latestIds = new ArrayList<>();
        volatile TftLeagueEntryDto league;

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(String url, HttpMethod m, HttpEntity<?> e, Class<T> type, Object... v) {
            if (type == RiotAccountDto.class) {
                RiotAccountDto a = new RiotAccountDto();
                a.setGameName("bench");
                a.setTagLine("KR1");
                a.setPuuid(puuid);
                return ResponseEntity.ok((T) a);
            }
            if (type == SummonerDto.class) {
                SummonerDto s = new SummonerDto();
                s.setProfileIconId(1);
                s.setSummonerLevel(100);
                return ResponseEntity.ok((T) s);
            }
            throw new IllegalStateException("unexpected riot call: " + url);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(String url, HttpMethod m, HttpEntity<?> e,
                ParameterizedTypeReference<T> type, Object... v) {
            if (url.contains("/tft/league/")) return ResponseEntity.ok((T) List.of(league));
            if (url.contains("/matches/by-puuid/")) return ResponseEntity.ok((T) new ArrayList<>(latestIds));
            throw new IllegalStateException("unexpected riot call: " + url);
        }
    }

    @Test
    void bench() throws Exception {
        FakeRiot fake = new FakeRiot();
        ReflectionTestUtils.setField((Object) AopTestUtils.getUltimateTargetObject(summonerService), "restTemplate", fake);
        ReflectionTestUtils.setField((Object) AopTestUtils.getUltimateTargetObject(matchService), "restTemplate", fake);

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        int warm = Integer.getInteger("bench.warm", 5);
        int runs = Integer.getInteger("bench.runs", 15);
        int idx = 0;

        for (String puuid : System.getProperty("bench.puuids").split(",")) {
            idx++;
            fake.puuid = puuid;
            fake.latestIds = jdbc.queryForList(
                    "select g.ga_id from participant p join game_info g on g.ga_num = p.pa_ga_num "
                            + "where p.pa_puuid = ? order by g.ga_datetime desc limit 5",
                    String.class, puuid);
            // LP 기록 INSERT가 측정에 섞이지 않도록 마지막 기록과 같은 값을 돌려준다
            TftLeagueEntryDto lg = new TftLeagueEntryDto();
            lg.setQueueType("RANKED_TFT");
            lg.setRank("I");
            lg.setWins(100);
            lg.setLosses(100);
            List<java.util.Map<String, Object>> last = jdbc.queryForList(
                    "select tier, lp from lp_history where puuid = ? order by created_at desc limit 1", puuid);
            lg.setTier(last.isEmpty() ? "MASTER" : (String) last.get(0).get("tier"));
            lg.setLeaguePoints(last.isEmpty() ? 0 : ((Number) last.get(0).get("lp")).intValue());
            fake.league = lg;

            int total = jdbc.queryForObject("select count(*) from participant where pa_puuid = ?", Integer.class, puuid);
            int ranked = jdbc.queryForObject(
                    "select count(*) from participant p join game_info g on g.ga_num = p.pa_ga_num "
                            + "where p.pa_puuid = ? and g.queue_id = 1100",
                    Integer.class, puuid);

            // 한글 닉네임 인코딩 문제를 피하려고 경로에는 고정 이름을 쓴다(계정은 FakeRiot가 결정)
            String url = "/summoner/kr/bench" + idx + "/KR1";
            long[] ms = new long[runs];
            long stmts = 0, entities = 0, collections = 0;
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
                collections = stats.getCollectionFetchCount();
                status = r.getResponse().getStatus();
                bytes = r.getResponse().getContentAsByteArray().length;
                if (r.getResolvedException() != null) throw new RuntimeException(r.getResolvedException());
            }
            long[] sorted = ms.clone();
            Arrays.sort(sorted);
            System.out.println("BENCH puuid=" + puuid.substring(0, 8) + " matches=" + total + " ranked=" + ranked
                    + " status=" + status + " htmlBytes=" + bytes
                    + " sql=" + stmts + " entitiesLoaded=" + entities + " collectionsFetched=" + collections
                    + " medianMs=" + sorted[runs / 2] + " minMs=" + sorted[0] + " maxMs=" + sorted[runs - 1]
                    + " all=" + Arrays.toString(ms));
        }
    }
}
