package com.tft.web.repository;

import com.tft.web.domain.LpHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface LpHistoryRepository extends JpaRepository<LpHistory, Long> {
    List<LpHistory> findTop15ByPuuidOrderByCreatedAtDesc(String puuid);
    
    // 가장 최근 기록 하나 가져오기
    LpHistory findTopByPuuidOrderByCreatedAtDesc(String puuid);

    @Query(value = """
        SELECT *
        FROM (
            SELECT *, ROW_NUMBER() OVER (PARTITION BY puuid ORDER BY created_at DESC) as rn
            FROM lp_history
            WHERE created_at >= '2026-04-15 00:00:00'
        ) t
        WHERE t.rn = 1
        ORDER BY 
            CASE tier
                WHEN 'CHALLENGER' THEN 1
                WHEN 'GRANDMASTER' THEN 2
                WHEN 'MASTER' THEN 3
                WHEN 'DIAMOND' THEN 4
                WHEN 'EMERALD' THEN 5
                ELSE 6
            END,
            lp DESC
        LIMIT 300
        """, nativeQuery = true)
    List<LpHistory> findTopRankers();
}
