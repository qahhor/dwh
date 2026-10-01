package com.smartup24.cms.instance.fnd.api;

import java.util.Map;

/**
 * Putting work on the job queue. The jobs themselves are {@link FndJobHandler} beans; the read-only questions about
 * the queue are answered by {@code fnd.service.FndJobQueries}.
 *
 * <p>Part of the foundation's contract (plan 10/10, item 4.2): callers depend on this interface, not on the job
 * runner that implements it, which may move to another package.
 */
public interface FndJobQueue {

    /** Enqueues a scheduled job outside its schedule, e.g. from a deployment step or an on-demand check. */
    void enqueue(String scheduleCode);

    /**
     * Enqueues a one-off job with arguments, outside any schedule. It joins the caller's transaction, so the business
     * module's record and the job appear together or not at all. An unknown handler code is refused.
     */
    void enqueueOnce(String handlerCode, Map<String, Object> args);
}
