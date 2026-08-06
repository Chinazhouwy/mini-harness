package com.chinazhouwy.miniharness.finance.research.data;

import com.chinazhouwy.miniharness.finance.research.domain.MarketResearchDataset;

@FunctionalInterface
public interface MarketResearchDataSource {

    MarketResearchDataset load();
}
