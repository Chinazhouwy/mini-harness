package com.chinazhouwy.miniharness.finance.research.domain;

import java.util.Objects;

/** 一个可被研究的市场标的，以及杠杆字段的真实语义。 */
public record MarketInstrument(
        String id,
        String market,
        String symbol,
        String name,
        String currency,
        String leverageType,
        String leverageUnit,
        String leverageDefinition
) {

    public MarketInstrument {
        requireText(id, "id");
        requireText(market, "market");
        requireText(symbol, "symbol");
        requireText(name, "name");
        requireText(currency, "currency");
        requireText(leverageType, "leverageType");
        requireText(leverageUnit, "leverageUnit");
        requireText(leverageDefinition, "leverageDefinition");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNullElse(value, "").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
