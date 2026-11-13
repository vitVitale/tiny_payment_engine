package com.tpe.riskservice.domain.rules;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The configured rules by id: a snapshot of the {@code rules} table. */
public record RuleSet(Map<String, RuleConfig> byId) {

    public static final RuleSet EMPTY = new RuleSet(Map.of());

    public RuleSet {
        byId = Map.copyOf(byId);
    }

    public static RuleSet of(Collection<RuleConfig> configs) {
        return new RuleSet(configs.stream().collect(Collectors.toMap(RuleConfig::ruleId, Function.identity())));
    }

    public Optional<RuleConfig> get(String ruleId) {
        return Optional.ofNullable(byId.get(ruleId));
    }
}
