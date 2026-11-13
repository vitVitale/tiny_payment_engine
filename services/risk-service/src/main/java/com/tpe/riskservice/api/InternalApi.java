package com.tpe.riskservice.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An internal service API (specs/00-engineering-baseline.md §12.1): until M5 it relies on network isolation only and
 * takes requests without a token; it MUST NOT be reachable from outside the internal network. From M5 the callers'
 * client-credentials scopes of ADR-012 are checked, behind {@code tpe.security.internal-auth.enabled}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InternalApi {}
