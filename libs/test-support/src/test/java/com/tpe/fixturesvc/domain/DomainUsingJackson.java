package com.tpe.fixturesvc.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DomainUsingJackson {

    @JsonProperty("amount")
    private long amountMinor;
}
