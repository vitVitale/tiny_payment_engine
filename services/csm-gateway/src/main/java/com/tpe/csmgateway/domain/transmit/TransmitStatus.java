package com.tpe.csmgateway.domain.transmit;

/**
 * Where the transmission of a journalled message stands (specs/15-csm-gateway.md §5, §6.5): {@code PENDING} until the
 * CSM takes it ({@code DELIVERED}) or its give-up window closes ({@code GAVE_UP}).
 */
public enum TransmitStatus { PENDING, DELIVERED, GAVE_UP }
