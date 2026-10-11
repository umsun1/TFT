package com.tft.web.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import com.tft.web.ratelimit.RiotCallRecorder;

@Configuration
public class RiotRestTemplateConfig {

    /** Riot API 전용 RestTemplate. 호출이 Batch와 공유하는 호출 기록에 남는다. */
    @Bean
    public RestTemplate riotRestTemplate(RiotCallRecorder recorder) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setInterceptors(List.of(recorder));
        return restTemplate;
    }
}
