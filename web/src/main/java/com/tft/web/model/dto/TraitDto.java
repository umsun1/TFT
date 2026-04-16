package com.tft.web.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TraitDto {
    private String name;
    private String description;
    private int num_units;
    private int style;
    private int tier_current;
    private String iconUrl;
    private String bgUrl;
}
