package com.tft.batch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.tft.batch.model.entity.MatchFetchQueue;

@Repository
public interface MatchFetchQueueRepository
        extends JpaRepository<MatchFetchQueue, Long> {

    boolean existsByMfqId(String mfqId);

    java.util.Optional<MatchFetchQueue> findByMfqId(String mfqId);

    @Modifying
    @Query(value = """
        UPDATE match_fetch_queue mfq
        JOIN (
            SELECT mfq_num
            FROM match_fetch_queue
            WHERE mfq_status = 'READY'
            ORDER BY mfq_priority DESC, mfq_num
            LIMIT 1
        ) t ON mfq.mfq_num = t.mfq_num
        SET mfq.mfq_status = 'FETCHING'
        """, nativeQuery = true)
    int pickNext();

    @Query(value = """
        SELECT *
        FROM match_fetch_queue
        WHERE mfq_status = 'FETCHING'
        ORDER BY mfq_updated_at ASC
        """, nativeQuery = true)
    java.util.List<MatchFetchQueue> findFetchingAll();
}
