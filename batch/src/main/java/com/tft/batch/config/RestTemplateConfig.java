package com.tft.batch.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import com.tft.batch.ratelimit.RiotRateLimitInterceptor;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RiotRateLimitInterceptor rateLimitInterceptor) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setInterceptors(List.of(rateLimitInterceptor));
        return restTemplate;
    }
}
