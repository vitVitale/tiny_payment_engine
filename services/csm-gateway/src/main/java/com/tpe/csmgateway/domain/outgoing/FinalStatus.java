package com.tpe.csmgateway.domain.outgoing;

/** The final status of a transfer, as {@code outgoing_transfers.final_status} records it (specs/15-csm-gateway.md §5). */
public enum FinalStatus { SETTLED, REJECTED }
