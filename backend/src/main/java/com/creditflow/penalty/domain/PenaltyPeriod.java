package com.creditflow.penalty.domain;

public enum PenaltyPeriod {
    DAY(1), WEEK(7);

    private final int days;

    PenaltyPeriod(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }
}
