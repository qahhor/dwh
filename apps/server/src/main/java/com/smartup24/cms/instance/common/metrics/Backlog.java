package com.smartup24.cms.instance.common.metrics;

/**
 * One sample of a queue or an outbox (plan 10/10, item 7.3): the items due now and how long the oldest of them has
 * been waiting past its due time, in seconds (0 when nothing is due).
 */
public record Backlog(long pending, double lagSeconds) {

    public static final Backlog EMPTY = new Backlog(0, 0);

    public Backlog {
        pending = Math.max(0, pending);
        lagSeconds = Math.max(0, lagSeconds);
    }
}
