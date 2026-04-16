package com.tft.web.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.annotation.PostConstruct;

@Service
public class TftStaticDataServiceImp implements TftStaticDataService {

    @Value("${riot.api.key}")
    private String apiKey;

    private Map<Integer, String> tacticianMap = new HashMap<>();
    // 유닛
    private Map<String, String> unitMap = new HashMap<>();
    private Map<String, String> unitNameMap = new HashMap<>();
    private Map<String, Integer> unitCostMap = new HashMap<>(); // [추가] 챔피언 비용 맵
    private java.util.List<com.tft.web.model.dto.ChampionDto> allChampions = new java.util.ArrayList<>();

    // 아이템
    private Map<String, String> itemMap = new HashMap<>();
    private Map<String, String> itemNameMap = new HashMap<>();
    // 시너지
    private Map<String, String> traitMap = new HashMap<>();
    private Map<String, String> traitNameMap = new HashMap<>();

    // CDragon Description Maps
    private Map<String, String> itemDescMap = new HashMap<>();
    private Map<String, String> traitDescMap = new HashMap<>();

    public static class UnitDetailData {
        public String desc;
        public String skillName;
        public String skillIcon;
        public int initialMana;
        public int mana;
        public int range;
        public java.util.List<String> traits;
    }

    private Map<String, UnitDetailData> unitDetailMap = new HashMap<>();

    private final String VERSION = "16.8.1";

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
            // 5. CDragon 설명 데이터 (상세 스킬, 아이템 스펙)
            loadCDragonData(restTemplate);

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

            // 시즌 17 기물만 필터링 (TFT17으로 시작하는 것만)
            if (!realId.startsWith("TFT17"))
                return;

            String imgFull = unitNode.get("image").get("full").asText();
            String koName = unitNode.get("name").asText();
            int cost = unitNode.has("tier") ? unitNode.get("tier").asInt() : 1;

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

        // 티버 수동 추가 (소환수라 JSON에 없을 수 있음) (16시즌 끝나서 무의미해짐)
        // if (allChampions.stream().noneMatch(c ->
        // c.getId().equals("TFT16_AnnieTibbers"))) {
        // allChampions.add(com.tft.web.model.dto.ChampionDto.builder()
        // .id("TFT16_AnnieTibbers")
        // .name("티버")
        // .cost(5) // 애니와 동일한 코스트로 가정
        // .imgUrl("https://cdn.lolchess.gg/upload/images/champions/TFT16_AnnieTibbers.jpg")
        // .traits(java.util.List.of())
        // .build());
        // }

        // 코스트 오름차순 정렬
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

    // CDragon 상세 설명 로더
    private void loadCDragonData(RestTemplate restTemplate) {
        String url = "https://raw.communitydragon.org/latest/cdragon/tft/ko_kr.json";
        try {
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);

            if (root.has("items")) {
                for (JsonNode item : root.get("items")) {
                    String apiName = item.path("apiName").asText(); // "TFT_Item_Bfs"
                    String desc = item.path("desc").asText();
                    if (!apiName.isEmpty()) {
                        itemDescMap.put(apiName, desc);
                    }
                }
            }

            if (root.has("sets")) {
                JsonNode sets = root.get("sets");
                sets.fields().forEachRemaining(entry -> {
                    JsonNode setNode = entry.getValue();
                    // 챔피언 스킬 설명
                    if (setNode.has("champions")) {
                        for (JsonNode champ : setNode.get("champions")) {
                            String apiName = champ.path("apiName").asText();
                            JsonNode ability = champ.path("ability");
                            JsonNode variables = ability.path("variables");

                            UnitDetailData detail = new UnitDetailData();
                            detail.skillName = ability.path("name").asText();
                            detail.skillIcon = ability.path("icon").asText();
                            detail.desc = parseDescVariables(ability.path("desc").asText(), variables);

                            if (champ.has("stats")) {
                                JsonNode stats = champ.path("stats");
                                detail.initialMana = stats.path("initialMana").asInt();
                                detail.mana = stats.path("mana").asInt();
                                detail.range = stats.path("range").asInt();
                            }

                            detail.traits = new java.util.ArrayList<>();
                            if (champ.has("traits") && champ.path("traits").isArray()) {
                                for (JsonNode tr : champ.path("traits")) {
                                    detail.traits.add(tr.asText());
                                }
                            }

                            if (!apiName.isEmpty()) {
                                unitDetailMap.put(apiName, detail);
                                // DDragon에 없는 소환수(비아, 바이엔 등) 대비 이름/초상화 보충
                                if (!unitNameMap.containsKey(apiName)) {
                                    unitNameMap.put(apiName, champ.path("name").asText());
                                    String tileIcon = champ.path("tileIcon").asText();
                                    if (tileIcon != null && !tileIcon.isEmpty()) {
                                        String cUrl = "https://raw.communitydragon.org/pbe/game/" + tileIcon
                                                .toLowerCase().replace(".dds", ".png").replace(".tex", ".png");
                                        unitMap.put(apiName, cUrl);
                                    }
                                }
                            }
                        }
                    }
                    // 시너지 설명
                    if (setNode.has("traits")) {
                        for (JsonNode trait : setNode.get("traits")) {
                            String apiName = trait.path("apiName").asText();
                            String desc = trait.path("desc").asText();
                            if (!apiName.isEmpty() && !desc.isEmpty()) {
                                traitDescMap.put(apiName, desc);
                            }
                        }
                    }
                });
            }
            System.out.println("- CDragon 정적 데이터(설명) 로드 완료");
        } catch (Exception e) {
            System.err.println("CDragon JSON 로드 중 에러: " + e.getMessage());
        }
    }

    private String formatNumber(double val) {
        if (Math.abs(val - Math.round(val)) < 0.01) {
            return String.format("%d", (long) Math.round(val));
        } else {
            return String.format("%.1f", val).replace(".0", "");
        }
    }

    private String parseDescVariables(String rawDesc, JsonNode variablesNode) {
        if (rawDesc == null || rawDesc.isEmpty())
            return "";
        if (variablesNode == null || !variablesNode.isArray())
            return rawDesc;

        Map<String, double[]> varMap = new HashMap<>();
        for (JsonNode var : variablesNode) {
            String name = var.path("name").asText().toLowerCase();
            JsonNode values = var.path("value");
            if (values != null && values.isArray() && values.size() > 3) {
                double[] arr = new double[] { values.get(1).asDouble(), values.get(2).asDouble(),
                        values.get(3).asDouble() };
                varMap.put(name, arr);
                varMap.put(name.replace("modified", ""), arr);
                varMap.put(name.replace("bonus", ""), arr);
            } else if (values != null && values.isArray() && values.size() > 0) {
                double[] arr = new double[] { values.get(0).asDouble() };
                varMap.put(name, arr);
                varMap.put(name.replace("modified", ""), arr);
                varMap.put(name.replace("bonus", ""), arr);
            }
        }

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@([A-Za-z0-9_]+)(\\*[0-9.]+)?@").matcher(rawDesc);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String varName = m.group(1).toLowerCase();
            String multStr = m.group(2); // e.g. "*100"
            double multiplier = 1.0;
            if (multStr != null && multStr.length() > 1) {
                try {
                    multiplier = Double.parseDouble(multStr.substring(1));
                } catch (Exception e) {
                }
            }

            double[] arr = varMap.get(varName);

            // 퍼지(Fuzzy) 매칭 로직 - TFT의 불규칙한 변수명 매칭을 해결
            if (arr == null) {
                String coreSearch = varName.replace("modified", "").replace("bonus", "");
                String bestKey = null;
                int bestScore = -1;

                for (String key : varMap.keySet()) {
                    if (key.equals("damage") || key.equals("magicdamage") || key.equals("physicaldamage") || key.equals("amount")) continue;
                    
                    int score = 0;
                    if (coreSearch.contains("secondary") && key.contains("secondary")) score += 20;
                    if (coreSearch.contains("passive") && key.contains("passive")) score += 20;
                    if (coreSearch.contains("heal") && key.contains("heal")) score += 20;
                    if (coreSearch.contains("shield") && key.contains("shield")) score += 20;
                    if (coreSearch.contains("armor") && key.contains("armor")) score += 20;
                    if (coreSearch.contains("mr") && key.contains("mr")) score += 20;

                    if (key.contains(coreSearch) || coreSearch.contains(key)) score += 10;

                    if (score > bestScore && score > 0) {
                        bestScore = score;
                        bestKey = key;
                    }
                }

                if (bestKey != null) {
                    arr = varMap.get(bestKey);
                } else {
                    // 최후의 폴백
                    if (varMap.containsKey(coreSearch)) arr = varMap.get(coreSearch);
                    else if (varMap.containsKey("damage")) arr = varMap.get("damage");
                    else if (varMap.containsKey("magicdamage")) arr = varMap.get("magicdamage");
                    else if (varMap.containsKey("physicaldamage")) arr = varMap.get("physicaldamage");
                    else if (varMap.containsKey("amount")) arr = varMap.get("amount");
                }
            }
            
            String replaceVal = "?";
            if (arr != null) {
                if (arr.length == 3) {
                    replaceVal = formatNumber(arr[0] * multiplier) + "/" + formatNumber(arr[1] * multiplier) + "/"
                            + formatNumber(arr[2] * multiplier);
                } else if (arr.length == 1) {
                    replaceVal = formatNumber(arr[0] * multiplier);
                }
            }
            m.appendReplacement(sb, replaceVal);
        }
        m.appendTail(sb);

        return sb.toString()
                .replace("%i:scaleAP%", "<span class='text-[#c084fc] font-bold'>🪄</span>")
                .replace("%i:scaleAD%", "<span class='text-[#fb923c] font-bold'>⚔️</span>")
                .replace("%i:scaleArmor%", "<span class='text-[#fbbf24] font-bold'>🛡️</span>")
                .replace("%i:scaleMR%", "<span class='text-[#60a5fa] font-bold'>🌀</span>")
                .replace("%i:scaleHealth%", "<span class='text-[#4ade80] font-bold'>❤️</span>");
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
        if (fileName == null) {
            return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/profileicon/1.png";
        }
        if (fileName.startsWith("http"))
            return fileName;
        return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-champion/" + fileName;
    }

    @Override
    public String getItemImgUrl(int itemId) {
        String fileName = itemMap.get(itemId);
        if (fileName == null)
            return "";
        return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-item/" + fileName;
    }

    @Override
    public String getItemImgUrlByName(String itemName) {
        if (itemName == null || itemName.isEmpty())
            return "";
        return "https://ddragon.leagueoflegends.com/cdn/" + VERSION + "/img/tft-item/" + itemName + ".png";
    }

    @Override
    public String getTraitIconUrl(String traitName) {
        if (traitName == null)
            return "";
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
    public String findTraitIdByAlias(String engAlias) {
        if (engAlias == null || engAlias.isEmpty()) return null;
        String lowerAlias = engAlias.toLowerCase();
        for (String key : traitMap.keySet()) {
            if (key.toLowerCase().contains(lowerAlias)) {
                return key;
            }
        }
        return null;
    }

    @Override
    public String getItemKoName(String englishName) {
        return itemNameMap.getOrDefault(englishName, englishName);
    }

    @Override
    public String getUnitKoName(String characterId) {
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

    @Override
    public String getUnitDesc(String characterId) {
        UnitDetailData u = unitDetailMap.get(characterId);
        return u != null ? u.desc : "";
    }

    public UnitDetailData getUnitDetail(String characterId) {
        return unitDetailMap.get(characterId);
    }

    @Override
    public String getItemDesc(String itemName) {
        // itemName(또는 itemId 형태)로 매핑. 아이템은 기본적으로 한글 이름 또는 TFT_Item_.. 로 매핑됨.
        // 현재 Community Dragon은 item의 apiName을 고유키로 사용함. (예: TFT_Item_Bfs)
        return itemDescMap.getOrDefault(itemName, "");
    }

    @Override
    public String getTraitDesc(String traitId) {
        return traitDescMap.getOrDefault(traitId, "");
    }

}