package com.smartup24.cms.instance.fnd.jobs;

/**
 * A failure another attempt would not fix (plan 10/10, item 3.8): the handler has already settled the outcome, e.g.
 * closed its package with an internal error. The runner records the run as failed with the cause and marks the job
 * failed at once instead of retrying a no-op.
 */
public class FndJobNotRetryableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FndJobNotRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
