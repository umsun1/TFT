package com.tft.web.config;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tft.web.model.dto.RiotAccountDto;
import com.tft.web.model.dto.SummonerDto;
import com.tft.web.model.dto.TftLeagueEntryDto;

/**
 * 소환사 정보 Redis 캐시 설정.
 * - 키 prefix: "cache:{캐시이름}:" (기존 대기열 키 tft:queue:* 와 분리)
 * - 캐시별 TTL: application.properties 의 app.cache.ttl.* (기본값: account 24h / league 2m / summoner 10m)
 * - null 결과는 캐싱하지 않음
 * - Redis 장애 시 캐시 오류는 로그만 남기고 원래 메서드(Riot API 호출)로 진행
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Value("${app.cache.ttl.account:24h}")
    private Duration accountTtl;

    @Value("${app.cache.ttl.league:2m}")
    private Duration leagueTtl;

    @Value("${app.cache.ttl.summoner:10m}")
    private Duration summonerTtl;

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(cacheName -> CacheNames.KEY_PREFIX + cacheName + ":")
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configs = Map.of(
                CacheNames.ACCOUNT, typed(defaults, mapper, RiotAccountDto.class, accountTtl),
                CacheNames.LEAGUE, typed(defaults, mapper, TftLeagueEntryDto.class, leagueTtl),
                CacheNames.SUMMONER, typed(defaults, mapper, SummonerDto.class, summonerTtl));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(10)))
                .withInitialCacheConfigurations(configs)
                .build();
    }

    private static <T> RedisCacheConfiguration typed(RedisCacheConfiguration base, ObjectMapper mapper,
            Class<T> type, Duration ttl) {
        return base.entryTtl(ttl)
                .serializeValuesWith(SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, type)));
    }

    /** Redis 연결 실패 등 캐시 오류가 페이지 오류로 번지지 않도록 로그만 남긴다. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("[cache] GET 실패 cache={} key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("[cache] PUT 실패 cache={} key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("[cache] EVICT 실패 cache={} key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("[cache] CLEAR 실패 cache={}: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
