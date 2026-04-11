package com.quant.strategy.domain.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "strategy_config")
public class StrategyConfigEntity {

    @Id
    private String id;

    private String name;

    private String type;

    @Column(length = 10000)
    private String params;

    private boolean enabled;

    @Column(name = "asset_class")
    private String assetClass;
}
