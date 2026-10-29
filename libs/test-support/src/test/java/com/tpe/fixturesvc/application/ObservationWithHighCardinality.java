package com.tpe.fixturesvc.application;

import io.micrometer.observation.Observation;

public class ObservationWithHighCardinality {

    void tag(Observation observation, String iban) {
        observation.highCardinalityKeyValue("iban", iban);
    }
}
