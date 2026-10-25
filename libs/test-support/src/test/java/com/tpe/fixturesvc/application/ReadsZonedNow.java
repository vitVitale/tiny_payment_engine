package com.tpe.fixturesvc.application;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ReadsZonedNow {

    public ZonedDateTime acceptedAt() {
        return ZonedDateTime.now(ZoneId.of("Europe/Berlin"));
    }
}
