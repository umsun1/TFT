package com.tft.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 타임아웃 기본값이 설정 파일·환경변수 없이도 실제 Redis 연결에 적용되는지 검증한다.
 * (Spring Boot의 Redis 자동 설정을 그대로 띄운다)
 */
class RedisTimeoutConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisTimeoutConfig.class);

    @Test
    void appliesOneSecondTimeoutsWhenNothingIsConfigured() {
        contextRunner.run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);

            assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(factory.getClientConfiguration().getClientOptions()).hasValueSatisfying(options ->
                    assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(1)));
        });
    }

    @Test
    void explicitSettingOverridesDefault() {
        contextRunner.withPropertyValues("spring.data.redis.timeout=3s").run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);

            assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void commandGivesUpAfterAboutOneSecondWhenRedisNeverResponds() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0)) {
            List<Socket> accepted = new CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        accepted.add(silentServer.accept()); // 연결만 받고 응답하지 않는다
                    }
                } catch (java.io.IOException ignored) {
                    // 테스트 종료
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            contextRunner
                    .withPropertyValues("spring.data.redis.host=localhost",
                            "spring.data.redis.port=" + silentServer.getLocalPort())
                    .run(context -> {
                        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);

                        long start = System.currentTimeMillis();
                        assertThatThrownBy(() -> redis.opsForValue().get("any")).isInstanceOf(RuntimeException.class);
                        long elapsed = System.currentTimeMillis() - start;

                        assertThat(elapsed).as("기본 60초가 아니라 1초 근처에서 포기").isLessThan(5_000);
                    });
            for (Socket s : accepted) {
                s.close();
            }
        }
    }
}
