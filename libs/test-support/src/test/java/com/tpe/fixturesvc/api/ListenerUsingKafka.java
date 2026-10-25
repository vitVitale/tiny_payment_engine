package com.tpe.fixturesvc.api;

import org.springframework.kafka.annotation.KafkaListener;

public class ListenerUsingKafka {

    @KafkaListener(topics = "payments.events")
    public void on(String message) {}
}
