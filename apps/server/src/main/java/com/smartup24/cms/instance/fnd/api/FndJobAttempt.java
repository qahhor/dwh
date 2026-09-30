package com.smartup24.cms.instance.fnd.api;

/**
 * Which attempt of a job a handler runs (plan 10/10, item 3.8). A handler that closes its business record on failure
 * uses it to leave a transient failure to the runner's retry while attempts remain and to close the record only on
 * the last one.
 *
 * @param number      the attempt, from 1
 * @param maxAttempts how many attempts the runner makes before it marks the job failed
 */
public record FndJobAttempt(int number, int maxAttempts) {

    public FndJobAttempt {
        if (number < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("attempt and max attempts start at 1");
        }
    }

    /** A handler called outside the queue (a test, a tool): the only attempt, so it is also the last. */
    public static FndJobAttempt only() {
        return new FndJobAttempt(1, 1);
    }

    /** No attempt follows this one: a failure now is final. */
    public boolean last() {
        return number >= maxAttempts;
    }
}
