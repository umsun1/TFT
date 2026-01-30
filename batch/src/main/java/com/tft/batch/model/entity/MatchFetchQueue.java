package com.tft.batch.model.entity;

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
    private String mfqId;

    @Column(name = "mfq_type")
    private String mfqType;

    @Column(name = "mfq_status")
    private String mfqStatus;

    @Column(name = "mfq_priority")
    private int mfqPriority;

    @Column(name = "mfq_updated_at")
    private LocalDateTime mfqUpdatedAt;

    public void markProcessing() {
        this.mfqStatus = "FETCHING";
    }

    public void markDone() {
        this.mfqStatus = "DONE";
    }

    public void markFail() {
        this.mfqStatus = "FAIL";
    }

    public void markReady() {
        this.mfqStatus = "READY";
    }
}