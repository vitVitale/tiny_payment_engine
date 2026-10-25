package com.tpe.fixturesvc.application;

import org.springframework.transaction.annotation.Transactional;

@Transactional
public class TransactionalPaymentService {

    @Transactional
    public void accept() {}
}
