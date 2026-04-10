package com.quant.strategy.dao;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KlineDao {

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

}