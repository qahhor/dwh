package com.smartup24.cms.instance.common.cluster;

import java.util.function.Consumer;

/**
 * Notices between the nodes of a cluster (plan 10/10, item 3.13, ADR-0025). A module that keeps a node-local copy of
 * shared data, such as the search settings, publishes a notice when the data changes and re-reads it when another
 * node publishes one. A module that serves a node-local connection, such as the SSE streams, sends a short message
 * that names a record, and every other node reads that record itself. The platform implements both on the cache
 * invalidation channel: one listening connection per node.
 */
public interface ClusterNotices {

    /**
     * Tells the other nodes that {@code name} changed. Inside a transaction the notice leaves only when it commits and
     * never after a rollback.
     */
    void publish(String name);

    /** Runs {@code handler} when another node publishes {@code name}, and after a lost connection to the others. */
    void onNotice(String name, Runnable handler);

    /**
     * Sends {@code message} on {@code topic} to the other nodes, with the commit rule of {@link #publish}. A message
     * carries identifiers only, never the data itself: a receiver reads the record and checks it. A message sent while
     * a node does not listen is lost for that node.
     */
    void send(String topic, String message);

    /** Runs {@code handler} with each message another node sends on {@code topic}. */
    void onMessage(String topic, Consumer<String> handler);
}
