package com.tft.web.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "match_fetch_queue", indexes = @Index(name = "idx_mfq_status_priority", columnList = "mfq_status, mfq_priority DESC"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchFetchQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mfq_num")
    private Long mfqNum;

    @Column(name = "mfq_id")
    private String mfqId; // PUUID or MatchID

    @Column(name = "mfq_type")
    private String mfqType; // "SUMMONER" (fetch match IDs) or "MATCH" (fetch match detail)

    @Column(name = "mfq_status")
    private String mfqStatus; // "READY", "FETCHING", "DONE", "FAIL"

    @Column(name = "mfq_priority")
    private int mfqPriority; // High priority for searched users

    @Column(name = "mfq_updated_at")
    private LocalDateTime mfqUpdatedAt;

    @PrePersist
    @PreUpdate
    public void updateTimestamp() {
        this.mfqUpdatedAt = LocalDateTime.now();
    }
}
