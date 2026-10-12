package com.tft.web.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import com.tft.web.ratelimit.RiotRateLimitInterceptor;

@Configuration
public class RiotRestTemplateConfig {

    /**
     * Riot API 전용 RestTemplate. Web의 Riot 호출은 모두 이 빈을 쓴다.
     * 호출 직전에 Batch와 공유하는 제한기를 통과해야 하고, 통과 못 하면 HTTP 요청이 나가지 않는다.
     */
    @Bean
    public RestTemplate riotRestTemplate(RiotRateLimitInterceptor rateLimitInterceptor) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setInterceptors(List.of(rateLimitInterceptor));
        return restTemplate;
    }
}
