package com.quant.strategy.domain.repository;

import com.quant.strategy.domain.entity.StrategyConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfigEntity, String> {
    
    List<StrategyConfigEntity> findByEnabledTrue();
}
