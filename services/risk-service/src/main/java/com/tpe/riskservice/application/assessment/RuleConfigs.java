package com.tpe.riskservice.application.assessment;

import com.tpe.riskservice.domain.rules.RuleSet;

/** The {@code rules} table (specs/14-risk-service.md §5). */
public interface RuleConfigs {

    RuleSet loadAll();
}
