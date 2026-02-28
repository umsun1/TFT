package com.tft.web.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.tft.web.domain.Participant;
import com.tft.web.model.dto.MatchApiDto;

@Repository
public interface ParticipantRepository extends JpaRepository<Participant, Integer> {

        List<Participant> findByPaPuuidAndGameInfo_GaDatetimeAfter(String puuid, LocalDateTime of);

    

        List<Participant> findByPaPuuid(String puuid);

    

        Page<MatchApiDto> findByPaPuuidOrderByGameInfo_GaDatetimeDesc(String puuid, Pageable pageable);

        @org.springframework.data.jpa.repository.Query("SELECT p FROM Participant p JOIN FETCH p.gameInfo WHERE p.paPuuid = :puuid ORDER BY p.gameInfo.gaDatetime DESC")
        Page<Participant> findByPaPuuidOrderByGameInfoGaDatetimeDesc(String puuid, org.springframework.data.domain.Pageable pageable);

        @org.springframework.data.jpa.repository.Query("SELECT p FROM Participant p JOIN FETCH p.gameInfo WHERE p.paPuuid = :puuid AND p.gameInfo.queueId = :queueId ORDER BY p.gameInfo.gaDatetime DESC")
        Page<Participant> findByPaPuuidAndGameInfo_QueueIdOrderByGameInfo_GaDatetimeDesc(String puuid, Integer queueId, org.springframework.data.domain.Pageable pageable);

        int countByPaPuuid(String puuid);

        @org.springframework.data.jpa.repository.Query("SELECT p FROM Participant p WHERE p.paName = :name ORDER BY p.gameInfo.gaDatetime DESC")
        List<Participant> findByPaName(String name);

    @org.springframework.data.jpa.repository.Query("SELECT " +
           "COUNT(p) as totalCount, " +
           "AVG(p.paPlacement) as avgPlacement, " +
           "SUM(CASE WHEN p.paPlacement = 1 THEN 1L ELSE 0L END) as winCount, " +
           "SUM(CASE WHEN p.paPlacement <= 4 THEN 1L ELSE 0L END) as top4Count " +
           "FROM Participant p JOIN p.gameInfo g " +
           "WHERE p.paPuuid = :puuid AND (:queueId = 0 OR g.queueId = :queueId)")
    com.tft.web.model.dto.SummonerStatsDto getSummonerStats(@org.springframework.data.repository.query.Param("puuid") String puuid, @org.springframework.data.repository.query.Param("queueId") Integer queueId);

    @org.springframework.data.jpa.repository.Query("SELECT p FROM Participant p JOIN FETCH p.gameInfo WHERE p.paPuuid = :puuid AND (:queueId = 0 OR p.gameInfo.queueId = :queueId) ORDER BY p.gameInfo.gaDatetime DESC")
    List<Participant> findRecentMatchesByPuuid(@org.springframework.data.repository.query.Param("puuid") String puuid, @org.springframework.data.repository.query.Param("queueId") Integer queueId, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(value = """
        SELECT pa_puuid as paPuuid, pa_name as paName, pa_tag as paTag, pa_companion_id as paCompanionId
        FROM (
            SELECT pa_puuid, pa_name, pa_tag, pa_companion_id,
                   ROW_NUMBER() OVER (PARTITION BY pa_puuid ORDER BY pa_ga_num DESC) as rn
            FROM participant
            WHERE pa_puuid IN :puuids
        ) t
        WHERE t.rn = 1
        """, nativeQuery = true)
    List<com.tft.web.model.dto.ParticipantSimpleDto> findLatestParticipantsByPuuids(@org.springframework.data.repository.query.Param("puuids") List<String> puuids);
}