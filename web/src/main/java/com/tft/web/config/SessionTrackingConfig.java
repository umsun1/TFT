package com.tft.web.config;

import java.util.Set;

import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.SessionTrackingMode;

@Configuration
public class SessionTrackingConfig {

    /**
     * 세션은 쿠키로만 추적한다.
     *
     * 검색 실패 시 안내 문구를 flash attribute(세션)에 담아 메인으로 리다이렉트하는데,
     * 쿠키가 아직 없는 첫 요청에서는 톰캣이 주소에 ";jsessionid=..."를 붙였고 그 주소("/;jsessionid=...")가 404가 됐다.
     * 그래서 처음 방문한 사용자는 안내 문구 대신 404를 봤다. URL에 세션 ID를 붙이지 않게 해서 막는다.
     */
    @Bean
    public ServletContextInitializer cookieOnlySessionTracking() {
        return servletContext -> servletContext.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
    }
}
