package com.tft.web.config;

/**
 * Redis 캐시 이름 모음.
 * 실제 Redis 키는 CacheConfig 의 prefix 규칙에 따라 "cache:{이름}:{키}" 형태가 된다.
 *   예) cache:account:hide on bush#kr1
 * 작업 대기열 키(tft:queue:*)와 네임스페이스가 겹치지 않는다.
 */
public final class CacheNames {

    public static final String KEY_PREFIX = "cache:";

    /** Riot ID(gameName#tagLine, 소문자) -> 계정(puuid) */
    public static final String ACCOUNT = "account";
    /** puuid -> 티어/LP */
    public static final String LEAGUE = "league";
    /** puuid -> 소환사 레벨/아이콘 */
    public static final String SUMMONER = "summoner";

    private CacheNames() {
    }
}
