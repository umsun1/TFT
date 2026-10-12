package com.tft.batch.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Redis(localhost:6379)에 붙어서 검증한다. Redis가 없으면 건너뛴다.
 * 테스트마다 다른 키 접두사를 써서 운영 키(riot:ratelimit:*)와 섞이지 않는다.
 */
class RiotRateLimiterTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private String prefix;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        boolean redisUp;
        try {
            redisUp = "PONG".equals(redis.getConnectionFactory().getConnection().ping());
        } catch (Exception e) {
            redisUp = false;
        }
        assumeTrue(redisUp, "Redis(localhost:6379)가 실행 중이어야 합니다");
        prefix = "test:" + UUID.randomUUID() + ":";
    }

    @AfterEach
    void tearDown() {
        if (prefix != null) {
            Set<String> keys = redis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        connectionFactory.destroy();
    }

    private RiotRateLimiter limiter(long longWindowMs, int longLimit, long shortWindowMs, int shortLimit) {
        return new RiotRateLimiter(redis, prefix, longWindowMs, longLimit, shortWindowMs, shortLimit, 0);
    }

    @Test
    void allowsCallsUpToLimitThenReportsWaitTime() {
        RiotRateLimiter limiter = limiter(60_000, 3, 1_000, 100);

        assertThat(limiter.tryAcquire()).isZero();
        assertThat(limiter.tryAcquire()).isZero();
        assertThat(limiter.tryAcquire()).isZero();

        long wait = limiter.tryAcquire();
        assertThat(wait).isBetween(1L, 60_000L);
    }

    @Test
    void budgetReturnsWhenWindowSlides() throws InterruptedException {
        RiotRateLimiter limiter = limiter(400, 2, 100, 100);
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertThat(limiter.tryAcquire()).isPositive();

        Thread.sleep(450);

        assertThat(limiter.tryAcquire()).isZero();
    }

    @Test
    void shortWindowLimitsBurst() {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 2);
        limiter.tryAcquire();
        limiter.tryAcquire();

        assertThat(limiter.tryAcquire()).isPositive();
    }

    @Test
    void peekDoesNotConsumeBudget() {
        RiotRateLimiter limiter = limiter(60_000, 1, 1_000, 100);

        assertThat(limiter.peekWaitMillis()).isZero();
        assertThat(limiter.peekWaitMillis()).isZero();
        assertThat(limiter.tryAcquire()).isZero();
        assertThat(limiter.peekWaitMillis()).isPositive();
    }

    @Test
    void callsRecordedByWebReduceBatchBudget() {
        // Web은 전체 한도(5), Batch는 그중 3만 쓰도록 설정하고 같은 키를 공유한다
        RiotRateLimiter web = limiter(60_000, 5, 1_000, 100);
        RiotRateLimiter batch = limiter(60_000, 3, 1_000, 100);

        web.tryAcquire();
        web.tryAcquire();
        assertThat(batch.tryAcquire()).isZero();

        // 합계 3건이 되어 Batch 몫은 끝났지만 Web은 계속 호출할 수 있다
        assertThat(batch.tryAcquire()).isPositive();
        assertThat(web.tryAcquire()).isZero();
    }

    @Test
    void blockedAfter429UntilRetryAfterPasses() throws InterruptedException {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 100);

        limiter.blockFor(Duration.ofMillis(300));
        assertThat(limiter.tryAcquire()).isBetween(1L, 300L);

        Thread.sleep(350);
        assertThat(limiter.tryAcquire()).isZero();
    }

    @Test
    void shorterBlockDoesNotShortenExistingLongerBlock() {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 100);

        limiter.blockFor(Duration.ofSeconds(60));
        limiter.blockFor(Duration.ofSeconds(2));

        Long ttl = redis.getExpire(prefix + RiotRateLimiter.BLOCKED_KEY, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isGreaterThan(50_000L);
    }

    @Test
    void longerBlockExtendsExistingBlock() {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 100);

        limiter.blockFor(Duration.ofSeconds(2));
        limiter.blockFor(Duration.ofSeconds(60));

        Long ttl = redis.getExpire(prefix + RiotRateLimiter.BLOCKED_KEY, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isGreaterThan(50_000L);
    }

    @Test
    void concurrentCallsNeverExceedLimit() throws Exception {
        RiotRateLimiter limiter = limiter(60_000, 30, 1_000, 1_000);
        int threads = 100;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger granted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                if (limiter.tryAcquire() == 0) {
                    granted.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(granted.get()).isEqualTo(30);
        assertThat(redis.opsForZSet().zCard(prefix + RiotRateLimiter.CALLS_KEY)).isEqualTo(30);
    }

    @Test
    void patientJobWaitsForBudgetInsteadOfFailing() {
        RiotRateLimiter limiter = limiter(300, 1, 100, 100);
        limiter.acquire();

        long start = System.currentTimeMillis();
        limiter.runPatiently(limiter::acquire);

        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(150);
    }

    @Test
    void acquireThrowsInsteadOfSleepingWhenWaitIsLong() {
        RiotRateLimiter limiter = limiter(60_000, 1, 1_000, 100);
        limiter.acquire();

        assertThatThrownBy(limiter::acquire).isInstanceOf(RiotRateLimitedException.class);
    }
}
