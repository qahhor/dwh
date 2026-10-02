package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.pagination.KeysetPage;
import tools.jackson.databind.JsonNode;

/**
 * What only the module knows about its entity's records (ADR-0019, roadmap item 56): who may see one, the list
 * page its screen gets and how one is deleted. The platform builds the entity's history, export and bulk delete
 * from its declaration and this one bean, so a module does not wire each of them by hand.
 */
public interface EntityRecords {

    /** The declared entity's code ({@code ms.notes}). */
    String entity();

    /**
     * Throws a not-found {@code ApiException} when the record does not exist or lies outside the viewer's data
     * scope, so neither its history nor its existence is revealed.
     */
    void requireVisible(long id);

    /** One page of the entity's list for the signed-in person, exactly as the list endpoint returns it. */
    default KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search) {
        throw new UnsupportedOperationException(entity() + " has no list export");
    }

    /** Deletes one record as the single delete endpoint does, with its checks and audit. */
    default void delete(long id) {
        throw new UnsupportedOperationException(entity() + " has no delete");
    }

    /** Archives one record as the single archive does, with its checks and audit (ADR-0032, 5.4). */
    default void archive(long id) {
        throw new UnsupportedOperationException(entity() + " has no archive");
    }

    /**
     * Changes one record from whatever revision it has, as a bulk action does (ADR-0032, 6.1): {@code update} with
     * the fields of {@code params}, or the declared record action {@code action} with its parameters — each with the
     * single change's checks, hooks, audit and event.
     */
    default void change(long id, String action, JsonNode params) {
        throw new UnsupportedOperationException(entity() + " has no bulk change " + action);
    }
}
