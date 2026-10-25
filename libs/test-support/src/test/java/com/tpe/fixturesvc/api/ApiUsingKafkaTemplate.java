package com.tpe.fixturesvc.api;

import org.springframework.kafka.core.KafkaTemplate;

public class ApiUsingKafkaTemplate {

    private KafkaTemplate<String, String> kafka;
}
