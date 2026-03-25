package com.tft.batch.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisQueueService {
    
    private final StringRedisTemplate redisTemplate;
    private static final String QUEUE_KEY = "tft:queue:ready";

    /**
     * 작업을 큐에 밀어 넣습니다.
     */
    public void pushTask(String id, String type, double priority) {
        String value = type + ":" + id;
        redisTemplate.opsForZSet().add(QUEUE_KEY, value, priority);
    }

    /**
     * 우선순위(score)가 가장 높은 작업을 꺼냅니다. (DB의 pickNext 역할)
     */
    public QueueTask popTask() {
        // 가장 큰 점수(우선순위)를 가진 요소를 하나 삭제하며 반환합니다.
        ZSetOperations.TypedTuple<String> tuple = redisTemplate.opsForZSet().popMax(QUEUE_KEY);
        if (tuple == null || tuple.getValue() == null) {
            return null;
        }

        String value = tuple.getValue();
        String[] parts = value.split(":", 2);
        if (parts.length != 2) return null;

        return new QueueTask(parts[1], parts[0], tuple.getScore());
    }

    /**
     * DTO for Redis Queue elements
     */
    public static class QueueTask {
        public String id;
        public String type;
        public double priority;

        public QueueTask(String id, String type, double priority) {
            this.id = id;
            this.type = type;
            this.priority = priority;
        }
    }
}
