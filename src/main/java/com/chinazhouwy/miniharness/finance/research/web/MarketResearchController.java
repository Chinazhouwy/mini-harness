package com.chinazhouwy.miniharness.finance.research.web;

import com.chinazhouwy.miniharness.finance.research.service.MarketResearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 真实跨市场杠杆与价格拟合的只读接口。 */
@RestController
@RequestMapping("/api/finance/research")
public final class MarketResearchController {

    private final MarketResearchService service;

    public MarketResearchController(MarketResearchService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping
    public MarketResearchView getResearch() {
        return service.getResearch();
    }
}
