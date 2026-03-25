package com.tft.batch.model.entity;

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
    private String rank_str;
    private int lp;

    private int wins;
    private int losses;
    private int profileIconId;

    private LocalDateTime createdAt;
}
