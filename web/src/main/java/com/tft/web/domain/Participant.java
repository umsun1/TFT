package com.tft.web.domain;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter @Setter
@NoArgsConstructor
@Table(name = "participant")
public class Participant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PA_NUM")
    private Integer paNum;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PA_GA_NUM")
    private GameInfo gameInfo;

    @Column(name = "PA_PUUID", nullable = false, length = 255)
    private String paPuuid;

    @Column(name = "PA_NAME", nullable = false, length = 50)
    private String paName;

    @Column(name = "PA_TAG", nullable = false, length = 10)
    private String paTag;

    @Column(name = "PA_COMPANION_ID")
    private Integer paCompanionId;

    @Column(name = "PA_PLACEMENT", nullable = false)
    private Integer paPlacement;

    @Column(name = "PA_GOLD", nullable = false)
    private Integer paGold;

    @Column(name = "PA_LEVEL", nullable = false)
    private Integer paLevel;

    // 유닛과 시너지 연결
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "participant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Unit> units = new ArrayList<>();

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "participant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Trait> traits = new ArrayList<>();
}