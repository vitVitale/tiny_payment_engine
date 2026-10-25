package com.tpe.fixturesvc.domain;

import java.util.Calendar;
import java.util.Date;

public class ReadsLegacyTime {

    public Date acceptedAt() {
        return new Date();
    }

    public Calendar today() {
        return Calendar.getInstance();
    }
}
