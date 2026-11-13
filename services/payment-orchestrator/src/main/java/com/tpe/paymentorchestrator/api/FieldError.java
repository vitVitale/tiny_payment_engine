package com.tpe.paymentorchestrator.api;

/** An entry of a Problem Details {@code errors} list (specs/00-engineering-baseline.md §7). */
public record FieldError(String field, String code) {}
