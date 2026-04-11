package com.quant.strategy.web.service;

import com.quant.strategy.domain.entity.StrategyConfigEntity;
import com.quant.strategy.domain.repository.StrategyConfigRepository;
import com.quant.strategy.api.dto.StrategyConfigDto;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class StrategyApplicationService {

    private final StrategyConfigRepository repository;

    public StrategyApplicationService(StrategyConfigRepository repository) {
        this.repository = repository;
    }

    public List<StrategyConfigDto> findAllEnabled() {
        return repository.findByEnabledTrue()
            .stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    public StrategyConfigDto getById(String id) {
        return repository.findById(id)
            .map(this::toDto)
            .orElse(null);
    }

    public StrategyConfigDto create(StrategyConfigDto dto) {
        StrategyConfigEntity entity = new StrategyConfigEntity();
        entity.setId(dto.getId());
        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setParams(dto.getParams());
        entity.setEnabled(dto.isEnabled());
        entity.setAssetClass(dto.getAssetClass());
        return toDto(repository.save(entity));
    }

    public StrategyConfigDto update(String id, StrategyConfigDto dto) {
        return repository.findById(id)
            .map(entity -> {
                entity.setName(dto.getName());
                entity.setType(dto.getType());
                entity.setParams(dto.getParams());
                entity.setEnabled(dto.isEnabled());
                entity.setAssetClass(dto.getAssetClass());
                return toDto(repository.save(entity));
            })
            .orElse(null);
    }

    private StrategyConfigDto toDto(StrategyConfigEntity entity) {
        StrategyConfigDto dto = new StrategyConfigDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setType(entity.getType());
        dto.setParams(entity.getParams());
        dto.setEnabled(entity.isEnabled());
        dto.setAssetClass(entity.getAssetClass());
        return dto;
    }
}
