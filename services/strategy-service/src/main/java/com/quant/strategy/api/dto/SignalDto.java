package com.quant.strategy.api.dto;

import com.quant.strategy.domain.enums.Signal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SignalDto {
    private String strategyId;
    private String symbol;
    private Signal signal;
    private Double confidence;
    private LocalDateTime timestamp;
}
