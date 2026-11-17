package com.tpe.csmgateway.domain.outgoing;

/**
 * Where a final status came from (specs/15-csm-gateway.md §6.4): the CSM's own {@code pacs.002}, or its answer to a
 * {@code pacs.028} the gateway sent.
 */
public enum StatusSource { STATUS_REPORT, INVESTIGATION }
