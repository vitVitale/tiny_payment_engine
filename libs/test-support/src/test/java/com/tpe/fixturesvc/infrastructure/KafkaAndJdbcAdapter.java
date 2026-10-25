package com.tpe.fixturesvc.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaAndJdbcAdapter {

    private JdbcClient jdbc;
    private KafkaTemplate<String, String> kafka;
}
