package com.tpe.riskservice.infrastructure.persistence;

import com.tpe.riskservice.application.assessment.RuleConfigs;
import com.tpe.riskservice.domain.rules.RuleAction;
import com.tpe.riskservice.domain.rules.RuleConfig;
import com.tpe.riskservice.domain.rules.RuleDirection;
import com.tpe.riskservice.domain.rules.RuleParameters;
import com.tpe.riskservice.domain.rules.RuleSet;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code rules} in PostgreSQL; {@code parameters} read as a JSON object (numbers as Integer, Long or Double). */
@Repository
class JdbcRuleConfigs implements RuleConfigs {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcRuleConfigs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RuleSet loadAll() {
        return RuleSet.of(jdbc.sql("SELECT rule_id, enabled, direction, parameters::text AS parameters, action FROM rules")
                .query((rs, n) -> new RuleConfig(
                        rs.getString("rule_id"),
                        rs.getBoolean("enabled"),
                        RuleDirection.valueOf(rs.getString("direction")),
                        new RuleParameters(json.readValue(rs.getString("parameters"), OBJECT)),
                        RuleAction.valueOf(rs.getString("action"))))
                .list());
    }
}
