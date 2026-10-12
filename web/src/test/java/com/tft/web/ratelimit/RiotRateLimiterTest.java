package com.tft.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
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
        return new RiotRateLimiter(redis, prefix, longWindowMs, longLimit, shortWindowMs, shortLimit);
    }

    private boolean tryAcquire(RiotRateLimiter limiter) {
        try {
            limiter.acquire();
            return true;
        } catch (RiotRateLimitedException e) {
            return false;
        }
    }

    // ── 기본 동작

    @Test
    void rejectsImmediatelyWithRemainingTimeWhenLimitIsReached() {
        RiotRateLimiter limiter = limiter(60_000, 3, 1_000, 100);
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();

        long start = System.currentTimeMillis();
        RiotRateLimitedException e = catchThrowableOfType(limiter::acquire, RiotRateLimitedException.class);

        assertThat(e).isNotNull();
        assertThat(e.isLimiterUnavailable()).isFalse();
        assertThat(e.getRetryAfterMillis()).isBetween(1L, 60_000L);
        assertThat(System.currentTimeMillis() - start).as("기다리지 않고 바로 거절").isLessThan(1_000);
    }

    @Test
    void rejectedCallIsNotRecorded() {
        RiotRateLimiter limiter = limiter(60_000, 2, 1_000, 100);
        limiter.acquire();
        limiter.acquire();
        tryAcquire(limiter);
        tryAcquire(limiter);

        assertThat(redis.opsForZSet().zCard(prefix + RiotRateLimiter.CALLS_KEY)).isEqualTo(2);
    }

    @Test
    void shortWindowLimitsBurst() {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 2);
        limiter.acquire();
        limiter.acquire();

        assertThatThrownBy(limiter::acquire).isInstanceOf(RiotRateLimitedException.class);
    }

    @Test
    void budgetReturnsWhenWindowSlides() throws InterruptedException {
        RiotRateLimiter limiter = limiter(400, 2, 100, 100);
        limiter.acquire();
        limiter.acquire();
        assertThat(tryAcquire(limiter)).isFalse();

        Thread.sleep(450);

        assertThat(tryAcquire(limiter)).isTrue();
    }

    // ── Web과 Batch의 공유

    @Test
    void webAndBatchShareOneCallRecord() {
        // 같은 키에 한도만 다르게: Batch는 합계 3에서 멈추고 Web은 합계 5까지 쓴다
        RiotRateLimiter batch = limiter(60_000, 3, 1_000, 100);
        RiotRateLimiter web = limiter(60_000, 5, 1_000, 100);

        assertThat(tryAcquire(batch)).isTrue();
        assertThat(tryAcquire(batch)).isTrue();
        assertThat(tryAcquire(web)).isTrue();      // 합계 3
        assertThat(tryAcquire(batch)).as("Batch 몫 소진").isFalse();
        assertThat(tryAcquire(web)).isTrue();      // 합계 4
        assertThat(tryAcquire(web)).isTrue();      // 합계 5
        assertThat(tryAcquire(web)).as("전체 한도 소진").isFalse();

        assertThat(redis.opsForZSet().zCard(prefix + RiotRateLimiter.CALLS_KEY)).isEqualTo(5);
    }

    @Test
    void batchSourceUsesSameKeysAndScripts() throws Exception {
        // 공통 모듈이 없어 두 모듈에 같은 코드를 두었다. 한쪽만 바뀌면 호출 기록을 공유하지 못하므로 어긋남을 잡는다.
        Path batchSource = Path.of("..", "batch", "src", "main", "java", "com", "tft", "batch", "ratelimit",
                "RiotRateLimiter.java");
        assumeTrue(Files.exists(batchSource), "batch 모듈 소스가 옆에 있어야 합니다");
        String batch = normalize(Files.readString(batchSource));

        assertThat(batch).contains("\"" + RiotRateLimiter.CALLS_KEY + "\"");
        assertThat(batch).contains("\"" + RiotRateLimiter.BLOCKED_KEY + "\"");
        assertThat(batch).contains(normalize(RiotRateLimiter.ACQUIRE_SCRIPT));
        assertThat(batch).contains(normalize(RiotRateLimiter.BLOCK_SCRIPT));
    }

    private String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    // ── 동시 요청

    @Test
    void concurrentRequestsNeverExceedLongWindowLimit() throws Exception {
        RiotRateLimiter limiter = limiter(60_000, 30, 1_000, 1_000);

        int granted = runConcurrently(100, () -> tryAcquire(limiter));

        assertThat(granted).isEqualTo(30);
        assertThat(redis.opsForZSet().zCard(prefix + RiotRateLimiter.CALLS_KEY)).isEqualTo(30);
    }

    @Test
    void concurrentRequestsNeverExceedShortWindowLimit() throws Exception {
        // 1초 구간 한도 검증. 테스트가 1초를 넘겨도 결과가 흔들리지 않게 구간을 5초로 둔다.
        RiotRateLimiter limiter = limiter(60_000, 1_000, 5_000, 7);

        int granted = runConcurrently(60, () -> tryAcquire(limiter));

        assertThat(granted).isEqualTo(7);
    }

    @Test
    void concurrentWebAndBatchTogetherStayWithinTotalLimit() throws Exception {
        RiotRateLimiter batch = limiter(60_000, 10, 1_000, 1_000);
        RiotRateLimiter web = limiter(60_000, 15, 1_000, 1_000);
        AtomicInteger batchGranted = new AtomicInteger();
        AtomicInteger counter = new AtomicInteger();

        int granted = runConcurrently(80, () -> {
            boolean isBatch = counter.getAndIncrement() % 2 == 0;
            boolean ok = tryAcquire(isBatch ? batch : web);
            if (ok && isBatch) {
                batchGranted.incrementAndGet();
            }
            return ok;
        });

        assertThat(granted).as("Web+Batch 합계").isEqualTo(15);
        assertThat(batchGranted.get()).as("Batch는 자기 몫까지만").isLessThanOrEqualTo(10);
    }

    private int runConcurrently(int threads, java.util.function.BooleanSupplier task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                if (task.getAsBoolean()) {
                    granted.incrementAndGet();
                }
                return null;
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown(); // 모든 스레드를 한꺼번에 출발시킨다
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return granted.get();
    }

    // ── Retry-After 차단

    @Test
    void blockedUntilRetryAfterPasses() throws InterruptedException {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 100);

        limiter.blockFor(Duration.ofMillis(300));
        RiotRateLimitedException e = catchThrowableOfType(limiter::acquire, RiotRateLimitedException.class);
        assertThat(e).isNotNull();
        assertThat(e.getRetryAfterMillis()).isBetween(1L, 300L);

        Thread.sleep(350);
        assertThatCode(limiter::acquire).doesNotThrowAnyException();
    }

    @Test
    void shorterBlockDoesNotShortenExistingLongerBlock() {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 100);

        limiter.blockFor(Duration.ofSeconds(60));
        limiter.blockFor(Duration.ofSeconds(2));

        Long ttl = redis.getExpire(prefix + RiotRateLimiter.BLOCKED_KEY, TimeUnit.MILLISECONDS);
        assertThat(ttl).isGreaterThan(50_000L);
    }

    @Test
    void longerBlockExtendsExistingBlock() {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 100);

        limiter.blockFor(Duration.ofSeconds(2));
        limiter.blockFor(Duration.ofSeconds(60));

        Long ttl = redis.getExpire(prefix + RiotRateLimiter.BLOCKED_KEY, TimeUnit.MILLISECONDS);
        assertThat(ttl).isGreaterThan(50_000L);
    }

    @Test
    void concurrentBlocksKeepTheLongestOne() throws Exception {
        RiotRateLimiter limiter = limiter(60_000, 100, 1_000, 100);
        AtomicInteger counter = new AtomicInteger();

        runConcurrently(40, () -> {
            int n = counter.getAndIncrement();
            limiter.blockFor(Duration.ofSeconds(n == 17 ? 90 : 1 + n % 5)); // 하나만 90초, 나머지는 1~5초
            return true;
        });

        Long ttl = redis.getExpire(prefix + RiotRateLimiter.BLOCKED_KEY, TimeUnit.MILLISECONDS);
        assertThat(ttl).isGreaterThan(80_000L);
    }

    // ── Redis 장애 (fail-closed)

    @Test
    void blocksCallQuicklyWhenRedisAcceptsConnectionButNeverResponds() throws Exception {
        // 연결은 받지만 아무 응답도 하지 않는 서버(멈춘 Redis 흉내)
        try (java.net.ServerSocket silentServer = new java.net.ServerSocket(0)) {
            List<java.net.Socket> accepted = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        accepted.add(silentServer.accept()); // 받기만 하고 읽지도 쓰지도 않는다
                    }
                } catch (java.io.IOException ignored) {
                    // 테스트 종료로 소켓이 닫힘
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            // 운영 설정과 같은 값: spring.data.redis.timeout=1s, connect-timeout=1s
            LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofSeconds(1))
                    .clientOptions(io.lettuce.core.ClientOptions.builder()
                            .socketOptions(io.lettuce.core.SocketOptions.builder()
                                    .connectTimeout(Duration.ofSeconds(1)).build())
                            .build())
                    .build();
            LettuceConnectionFactory hungFactory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("localhost", silentServer.getLocalPort()), clientConfig);
            hungFactory.afterPropertiesSet();
            StringRedisTemplate hungRedis = new StringRedisTemplate(hungFactory);
            hungRedis.afterPropertiesSet();
            try {
                RiotRateLimiter limiter = new RiotRateLimiter(hungRedis, prefix, 60_000, 100, 1_000, 100);

                long start = System.currentTimeMillis();
                RiotRateLimitedException e = catchThrowableOfType(limiter::acquire, RiotRateLimitedException.class);
                long elapsed = System.currentTimeMillis() - start;

                assertThat(e).isNotNull();
                assertThat(e.isLimiterUnavailable()).isTrue();
                assertThat(elapsed).as("타임아웃(1초) 근처에서 포기해야 한다").isLessThan(5_000);
            } finally {
                hungFactory.destroy();
                for (java.net.Socket s : accepted) {
                    s.close();
                }
            }
        }
    }

    @Test
    void blocksCallWhenRedisIsUnreachable() {
        // 아무도 듣지 않는 포트. 한도를 확인할 수 없으면 통과시키지 않는다.
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500)).build();
        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 6390), clientConfig);
        deadFactory.afterPropertiesSet();
        StringRedisTemplate deadRedis = new StringRedisTemplate(deadFactory);
        deadRedis.afterPropertiesSet();
        try {
            RiotRateLimiter limiter = new RiotRateLimiter(deadRedis, prefix, 60_000, 100, 1_000, 100);

            RiotRateLimitedException e = catchThrowableOfType(limiter::acquire, RiotRateLimitedException.class);

            assertThat(e).isNotNull();
            assertThat(e.isLimiterUnavailable()).isTrue();
            assertThat(e.getRetryAfterMillis()).isPositive();
        } finally {
            deadFactory.destroy();
        }
    }
}
