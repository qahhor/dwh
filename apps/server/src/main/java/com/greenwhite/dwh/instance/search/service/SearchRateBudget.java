package com.greenwhite.dwh.instance.search.service;

/** Effective rate and immediate capacity for one search owner type. */
public record SearchRateBudget(int perMinute, int capacity) {
    public SearchRateBudget {
        if (perMinute < 1 || capacity < 1 || capacity > perMinute) {
            throw new IllegalArgumentException("Search rate budget is invalid");
        }
    }
}
