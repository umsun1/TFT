package com.tft.batch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * Redis 타임아웃 기본값을 코드와 함께 배포한다(redis-defaults.properties).
 *
 * application.properties는 Git에 올라가지 않으므로 거기에만 두면 실행 환경마다 빠질 수 있다.
 * 여기 값은 우선순위가 가장 낮아서, 다른 곳에서 같은 키를 설정하면 그 값이 쓰인다.
 */
@Configuration
@PropertySource("classpath:redis-defaults.properties")
public class RedisTimeoutConfig {
}
