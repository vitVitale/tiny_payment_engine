package com.tpe.fixturesvc.api;

import org.springframework.transaction.annotation.Transactional;

public class ControllerWithTransactionalMethod {

    @Transactional
    public void accept() {}
}
