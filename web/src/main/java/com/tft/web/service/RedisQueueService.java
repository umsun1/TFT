package com.tft.web.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisQueueService {
    
    private final StringRedisTemplate redisTemplate;
    private static final String QUEUE_KEY = "tft:queue:ready";

    /**
     * 작업을 ZSet 기반 대기열에 추가합니다.
     * 이미 동일한 작업(type:puuid)이 있다면 우선순위(priority)만 갱신됩니다.
     */
    public void pushTask(String id, String type, double priority) {
        // "SUMMONER:puuid" 또는 "MATCH:matchId" 형식으로 저장
        String value = type + ":" + id;
        redisTemplate.opsForZSet().add(QUEUE_KEY, value, priority);
    }
}
