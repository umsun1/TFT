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
    private static final String RETRY_KEY = "tft:queue:retries";
    private static final String FAILED_KEY = "tft:queue:failed";

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
        if (parts.length != 2)
            return null;

        return new QueueTask(parts[1], parts[0], tuple.getScore());
    }

    /**
     * 실패 횟수를 1 올리고 올린 뒤의 값을 반환합니다.
     */
    public long incrementRetry(QueueTask task) {
        Long count = redisTemplate.opsForHash().increment(RETRY_KEY, task.type + ":" + task.id, 1);
        return count == null ? 1 : count;
    }

    public void clearRetry(QueueTask task) {
        redisTemplate.opsForHash().delete(RETRY_KEY, task.type + ":" + task.id);
    }

    /**
     * 더 재시도하지 않을 작업을 실패 목록에 보관합니다. (원인 확인 후 수동 재등록용)
     */
    public void markFailed(QueueTask task, String reason) {
        String field = task.type + ":" + task.id;
        redisTemplate.opsForHash().put(FAILED_KEY, field, java.time.LocalDateTime.now() + " " + reason);
        redisTemplate.opsForHash().delete(RETRY_KEY, field);
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
