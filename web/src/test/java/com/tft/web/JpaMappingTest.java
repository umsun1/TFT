package com.tft.web;

import com.tft.web.domain.*;
import com.tft.web.repository.GameInfoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@SpringBootTest
public class JpaMappingTest {

    @Autowired
    private GameInfoRepository gameInfoRepository;

    // @Test
    // @Transactional
    // @Rollback(false) // DB에 데이터가 남도록 설정 (Workbench에서 확인용)
    // @DisplayName("TFT 매치 데이터 계층 구조 저장 테스트")
    // void tftDataSaveTest() {
    //     // 1. 게임 정보 생성 (GameInfo 엔티티 필드에 맞춤)
    //     GameInfo game = new GameInfo();
    //     game.setGaId("KR_734561234");
    //     // game.setGaDatetime(LocalDateTime.now()); // LocalDateTime 타입
    //     game.setGaVersion("Version 14.24");

    //     // 2. 참가자 생성 (Participant 엔티티 필드에 맞춤)
    //     Participant p = new Participant();
    //     p.setPaPuuid("USER_PUUID_ABC123");
    //     p.setPaName("테스트유저");
    //     p.setPaTag("KR1");
    //     p.setPaPlacement(1);
    //     p.setPaLevel(9);
    //     p.setPaGold(35);
        
    //     // 양방향 연결
    //     p.setGameInfo(game); 
    //     game.getParticipants().add(p);

    //     // 3. 시너지 생성 (Trait 엔티티 필드에 맞춤)
    //     Trait trait = new Trait();
    //     trait.setTrName("Set13_Enforcer");
    //     trait.setTrNumUnits(6);
    //     trait.setTrStyle(3);
        
    //     // 양방향 연결
    //     trait.setParticipant(p);
    //     p.getTraits().add(trait);

    //     // 4. 유닛 생성 (Unit 엔티티 필드에 맞춤)
    //     Unit unit = new Unit();
    //     unit.setUnId("TFT13_Caitlyn");
    //     unit.setUnName("케이틀린");
    //     unit.setUnTier(2);
        
    //     // 양방향 연결
    //     unit.setParticipant(p);
    //     p.getUnits().add(unit);

    //     // 5. 아이템 생성 (Item 엔티티 필드에 맞춤)
    //     Item item = new Item();
    //     item.setItFirst("TFT_Item_InfinityEdge");
    //     item.setItSecond("TFT_Item_LastWhisper");
    //     item.setItThird("TFT_Item_GuardianAngel");
        
    //     // 양방향 연결
    //     item.setUnit(unit);
    //     unit.setItem(item);

    //     // 6. 저장 실행
    //     // CascadeType.ALL 덕분에 game만 저장해도 하위 데이터가 모두 저장됩니다.
    //     gameInfoRepository.save(game);
        
    //     System.out.println("===============================");
    //     System.out.println("TFT 매치 데이터 저장 완료!");
    //     System.out.println("Game ID: " + game.getGaId());
    //     System.out.println("===============================");
    // }

    // 연결된 DB의 매치 데이터를 전부 지운다. 수집 DB에 연결된 채 실행되면 복구할 수 없으므로 꺼 둔다.
    // 테스트 전용 DB를 갖춘 뒤에만 다시 켤 것.
    @org.junit.jupiter.api.Disabled("수집 DB의 데이터를 전부 삭제함. 테스트 전용 DB에서만 실행할 것")
    @Test
    void 데이터_전체_삭제() {
        gameInfoRepository.deleteAll(); // 모든 게임 정보와 그에 속한 자식들까지 한 번에 삭제
    }
}