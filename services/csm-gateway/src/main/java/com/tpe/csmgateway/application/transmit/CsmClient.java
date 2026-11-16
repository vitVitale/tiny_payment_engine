package com.tpe.csmgateway.application.transmit;

/** The CSM transport, {@code POST {tpe.csm.base-url}/csm/v1/messages} (specs/15-csm-gateway.md §3.1). */
public interface CsmClient {

    /**
     * Sends one envelope.
     *
     * @throws CsmUnavailableException if the CSM does not acknowledge it with {@code 2xx}: an error status, a
     *     timeout or no connection
     */
    void send(String envelope);

    /** The CSM did not take the envelope; it may or may not have received it. */
    final class CsmUnavailableException extends RuntimeException {

        public CsmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
