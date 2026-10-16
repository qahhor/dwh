package com.smartup24.cms.instance.common.security;

/** Bounded per-process request budgets without depending on security wiring (ADR-0039). */
public interface RequestBudget {
    boolean consume(String key, int perMinute, int capacity);
}
