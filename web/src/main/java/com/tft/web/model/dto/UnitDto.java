package com.tft.web.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnitDto {
    @JsonProperty("character_id")
    private String characterId;
    
    @JsonProperty("tier")
    private int star;
    
    @JsonProperty("rarity")
    private int cost;
    
    private String championImg;
    private String championName;
    private String description;
    private String skillName;
    private String skillIcon;
    private int initialMana;
    private int mana;
    private int range;
    private List<String> traits;
    private List<String> traitIconUrls;
    
    @JsonProperty("itemNames")
    private List<String> items;
    
    private List<String> itemDescriptions;
    
    private List<String> itemImgUrls;
}