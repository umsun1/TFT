package com.tft.web.service;

import java.util.List;

import com.tft.web.model.dto.ChampionDto;

public interface TftStaticDataService {
    String getTacticianImgUrl(int itemId);
    String getUnitImgUrl(String characterId);
    String getItemImgUrl(int itemId);
    String getItemImgUrlByName(String itemName);
    String getTraitIconUrl(String traitName);
    String getTraitKoName(String traitId);
    String getItemKoName(String itemName);
    String getUnitKoName(String englishName);
    
    // Alias for getUnitImgUrl
    default String getChampionIconUrl(String name) { return getUnitImgUrl(name); }
    default String getChampionKoName(String name) { return getUnitKoName(name); }
    
    int getChampionCost(String name);

    List<ChampionDto> getAllChampions();
}