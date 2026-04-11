package com.quant.strategy.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyConfigDto {
    private String id;
    private String name;
    private String type;
    private String params;
    private boolean enabled;
    private String assetClass;
}
