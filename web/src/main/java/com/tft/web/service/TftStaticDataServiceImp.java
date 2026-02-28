package com.tft.web.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.annotation.PostConstruct;


@Service
public  class TftStaticDataServiceImp implements TftStaticDataService{

    @Value("${riot.api.key}")
    private String apiKey;

    private Map<Integer, String> tacticianMap = new HashMap<>();
    //유닛
    private Map<String, String> unitMap = new HashMap<>();
    private Map<String, String> unitNameMap = new HashMap<>();
    private Map<String, Integer> unitCostMap = new HashMap<>(); // [추가] 챔피언 비용 맵
    private java.util.List<com.tft.web.model.dto.ChampionDto> allChampions = new java.util.ArrayList<>();

    //아이템
    private Map<String, String> itemMap = new HashMap<>();
    private Map<String, String> itemNameMap = new HashMap<>();
    //시너지
    private Map<String, String> traitMap = new HashMap<>();
    private Map<String, String> traitNameMap = new HashMap<>();
    
    private final String VERSION = "16.4.1";

    @PostConstruct
    public void init() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            // 1. 전설이
            loadTacticianData(restTemplate);
            // 2. 유닛(챔피언)
            loadUnitData(restTemplate);
            // 3. 아이템
            loadItemData(restTemplate);
            // 4. 시너지
            loadTraitData(restTemplate);

            System.out.println(">>> 모든 정적 데이터 로드 완료!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // 전설이 데이터 전용 로더
    private void loadTacticianData(RestTemplate restTemplate) {
        String url = "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/data/ko_KR/tft-tactician.json";
        JsonNode root = restTemplate.getForObject(url, JsonNode.class);
        JsonNode data = root.get("data");

        data.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            int itemId = node.get("id").asInt();
            String imgFileName = node.get("image").get("full").asText();
            tacticianMap.put(itemId, imgFileName);
        });
        System.out.println("- 전설이 데이터 로드 완료 (" + tacticianMap.size() + "개)");
    }
    // 챔피언 데이터 전용 로더
    private void loadUnitData(RestTemplate restTemplate) {
        String url = "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/data/ko_KR/tft-champion.json";
        JsonNode root = restTemplate.getForObject(url, JsonNode.class);
        JsonNode data = root.get("data");

        data.fields().forEachRemaining(entry -> {
            JsonNode unitNode = entry.getValue();
            
            // entry.getKey() 대신 노드 안의 "id" 필드를 가져옵니다.
            String realId = unitNode.get("id").asText(); // "TFT15_Aatrox"

            // 시즌 16 기물만 필터링 (TFT16으로 시작하는 것만)
            if (!realId.startsWith("TFT16")) return;

            String imgFull = unitNode.get("image").get("full").asText();
            String koName = unitNode.get("name").asText();
            int cost = unitNode.has("tier") ? unitNode.get("tier").asInt() : 1; // [추가] 비용 파싱

            unitMap.put(realId, imgFull);
            unitNameMap.put(realId, koName);
            unitCostMap.put(realId, cost);

            // ChampionDto 생성 및 리스트 추가
            java.util.List<String> traits = new java.util.ArrayList<>();
            if (unitNode.has("traits")) {
                unitNode.get("traits").forEach(t -> traits.add(t.asText()));
            }

            allChampions.add(com.tft.web.model.dto.ChampionDto.builder()
                    .id(realId)
                    .name(koName)
                    .cost(cost)
                    .imgUrl("https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-champion/" + imgFull)
                    .traits(traits)
                    .build());
        });
        
        // 티버 수동 추가 (소환수라 JSON에 없을 수 있음)
        if (allChampions.stream().noneMatch(c -> c.getId().equals("TFT16_AnnieTibbers"))) {
            allChampions.add(com.tft.web.model.dto.ChampionDto.builder()
                    .id("TFT16_AnnieTibbers")
                    .name("티버")
                    .cost(5) // 애니와 동일한 코스트로 가정
                    .imgUrl("https://cdn.lolchess.gg/upload/images/champions/TFT16_AnnieTibbers.jpg")
                    .traits(java.util.List.of())
                    .build());
        }

        // [추가] 코스트 오름차순 정렬
        allChampions.sort(java.util.Comparator.comparingInt(com.tft.web.model.dto.ChampionDto::getCost));

        System.out.println("- 유닛 데이터 로드 완료 (" + unitMap.size() + "개)");
    }
    // 아이템 데이터 전용 로더
    private void loadItemData(RestTemplate restTemplate) {
        String url = "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/data/ko_KR/tft-item.json";
        JsonNode data = restTemplate.getForObject(url, JsonNode.class).get("data");
        data.fields().forEachRemaining(entry -> {
            JsonNode itemNode = entry.getValue();
            String actualId = itemNode.get("id").asText();
            
            // 이미지 파일명
            String imgFull = itemNode.get("image").get("full").asText();
            // 한글 이름
            String koName = itemNode.get("name").asText();
            
            itemMap.put(actualId, imgFull);
            itemNameMap.put(actualId, koName);
        });
        System.out.println("- 아이템 이미지/이름 로드 완료 (" + itemNameMap.size() + "개)");
    }

    // 시너지 데이터 전용 로더
    private void loadTraitData(RestTemplate restTemplate) {
        String url = "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/data/ko_KR/tft-trait.json";
        try {
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            JsonNode data = root.get("data");

            data.fields().forEachRemaining(entry -> {
                String key = entry.getKey(); // "TFT16_Glutton"
                JsonNode traitNode = entry.getValue();
                String imgFull = traitNode.get("image").get("full").asText(); // "Trait_Icon_16_Glutton..."
                
                traitMap.put(key, imgFull); // entry.getKey()를 쓰는 것이 더 확실할 수 있습니다.
                traitNameMap.put(key, traitNode.get("name").asText());
            });
            System.out.println("- 시너지 맵 생성 완료 (" + traitMap.size() + "개)");
        } catch (Exception e) {
            System.err.println("시너지 JSON 로드 중 에러: " + e.getMessage());
        }
    }

    @Override
    public String getTacticianImgUrl(int itemId) {
        String fileName = tacticianMap.get(itemId);
        if (fileName == null) {
            return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/profileicon/1.png";
        }
        return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-tactician/" + fileName;
    }

    @Override
    public String getUnitImgUrl(String characterId) {
        String fileName = unitMap.get(characterId);
        // 티버(AnnieTibbers) 예외 처리
        if (characterId.equalsIgnoreCase("TFT16_AnnieTibbers")) {
            return "https://cdn.lolchess.gg/upload/images/champions/TFT16_AnnieTibbers.jpg";
        }
        if (fileName == null) {
            return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/profileicon/1.png";
        }
        return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-champion/" + fileName;
    }

    @Override
    public String getItemImgUrl(int itemId) {
        String fileName = itemMap.get(itemId);
        if (fileName == null) return "";
        return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-item/" + fileName;
    }

    @Override
    public String getItemImgUrlByName(String itemName) {
        if (itemName == null || itemName.isEmpty()) return "";
        return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-item/" + itemName + ".png";
    }
    
    @Override
    public String getTraitIconUrl(String traitName) {
        if (traitName == null) return "";
        // 맵에서 실제 파일명을 찾습니다.
        String fileName = traitMap.get(traitName);
        
        // 맵에 없으면 기본 규칙으로 시도
        if (fileName == null) {
            fileName = traitName + ".png";
        }
        return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-trait/" + fileName;
    }

    @Override
    public String getTraitKoName(String traitId) {
        return traitNameMap.getOrDefault(traitId, traitId); // 없으면 ID 그대로 반환
    }
    @Override
    public String getItemKoName(String englishName) {
        return itemNameMap.getOrDefault(englishName, englishName);
    }

    @Override
    public String getUnitKoName(String characterId){
        return unitNameMap.getOrDefault(characterId, characterId);
    }
    @Override
    public int getChampionCost(String name) {
        return unitCostMap.getOrDefault(name, 1);
    }

    @Override
    public java.util.List<com.tft.web.model.dto.ChampionDto> getAllChampions() {
        return allChampions;
    }

}