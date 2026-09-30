package com.smartup24.cms.instance.common.cluster;

/**
 * Notices between the nodes of a cluster (plan 10/10, item 3.13, ADR-0025). A module that keeps a node-local copy of
 * shared data, such as the search settings, publishes a notice when the data changes and re-reads it when another
 * node publishes one. The platform implements it on the cache invalidation channel.
 */
public interface ClusterNotices {

    /**
     * Tells the other nodes that {@code name} changed. Inside a transaction the notice leaves only when it commits and
     * never after a rollback.
     */
    void publish(String name);

    /** Runs {@code handler} when another node publishes {@code name}, and after a lost connection to the others. */
    void onNotice(String name, Runnable handler);
}
