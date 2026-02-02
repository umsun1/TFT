package com.tft.batch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.tft.batch.model.entity.GameInfo;
import java.util.Optional;

public interface GameInfoRepository extends JpaRepository<GameInfo, Integer> {
    Optional<GameInfo> findByGaId(String gaId);
    boolean existsByGaId(String gaId);

    @org.springframework.data.jpa.repository.Query("SELECT g.gaId FROM GameInfo g WHERE g.gaId IN :gaIds")
    java.util.List<String> findExistingGaIds(@org.springframework.data.repository.query.Param("gaIds") java.util.List<String> gaIds);
}
