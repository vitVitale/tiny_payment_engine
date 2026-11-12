package com.tpe.systemtests.harness;

import tools.jackson.databind.JsonNode;

/** A service's response as the harness reads it: status and JSON body ({@code null} when empty). */
public record Response(int status, JsonNode body) {

    /** The {@code code} of a Problem Details body. */
    public String code() {
        return body == null || body.get("code") == null ? null : body.get("code").asString();
    }
}
