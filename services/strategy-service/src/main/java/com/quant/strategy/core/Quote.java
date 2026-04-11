package com.quant.strategy.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Quote {
    private String symbol;
    private BigDecimal price;
    private ZonedDateTime timestamp;
}
