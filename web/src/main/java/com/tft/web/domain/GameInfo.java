package com.tft.web.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter @Setter
@NoArgsConstructor
@Table(name = "game_info")
public class GameInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "GA_NUM")
    private Integer gaNum;

    @Column(name = "GA_ID", unique = true, nullable = false, length = 50)
    private String gaId;

    @Column(name = "GA_DATETIME", nullable = false)
    private LocalDateTime gaDatetime;

    @Column(name = "GA_VERSION", length = 255)
    private String gaVersion;

    @Column(name = "queue_id")
    private Integer queueId;

    // 양방향 관계: 부모인 게임정보를 지우면 참가자 정보도 같이 지워지도록 설정(Cascade)
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "gameInfo", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Participant> participants = new ArrayList<>();
}