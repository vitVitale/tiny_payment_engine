package com.tpe.paymentapi.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The {@code code} of a downstream Problem Details body (specs/00-engineering-baseline.md §7). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProblemCode(String code) {}
