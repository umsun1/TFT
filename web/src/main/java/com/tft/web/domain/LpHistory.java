package com.tft.web.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "lp_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LpHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "puuid", nullable = false)
    private String puuid;

    private String tier;
    private String rank_str; // 'rank'는 SQL 예약어일 수 있어 rank_str로 명명
    private int lp;

    private int wins;
    private int losses;
    private int profileIconId;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
