package com.smartup24.cms.instance.common.error;

/**
 * Marks an exception as a failure a later attempt may not meet: a system the application depends on is away for a
 * while (plan 10/10, item 3.8). The job queue retries such a failure instead of treating it as final; the marker lets a
 * module say so about its own exception without the queue knowing the module (plan 10/10, item 4.2).
 */
public interface TransientFailure {}
