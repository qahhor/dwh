package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.pagination.KeysetPage;
import tools.jackson.databind.JsonNode;

/**
 * The records of every entity declared with a table, as the general runtime keeps them (ADR-0032, 6.5; plan 10/10,
 * item 5.4): the registry builds the history, the export, the bulk actions and the file reads of such an entity from
 * it, so no module writes an {@link EntityRecords} bean for it. Each call acts as the signed-in person, in the entity's
 * scope, with the runtime's checks, audit and events.
 */
public interface EntityRecordStore {

    /** Throws a 404 {@code ApiException} when the record does not exist or lies outside the viewer's scope. */
    void requireVisible(EntityDefinition entity, long id);

    /** One page of the entity's list for the signed-in person, as {@code GET /api/v1/entities/{code}} answers it. */
    KeysetPage<?> page(EntityDefinition entity, int limit, String cursor, String filter, String sort, String search);

    /** Deletes one record as {@code DELETE /api/v1/entities/{code}/{id}} does, from whatever revision. */
    void delete(EntityDefinition entity, long id);

    /** Archives one record as the archive switch does, from whatever revision (ADR-0032, 5.4). */
    void archive(EntityDefinition entity, long id);

    /**
     * Changes one record from whatever revision it has: {@code update} with the fields of {@code params} as
     * {@code PATCH} does, any other code as that record action does (ADR-0032, 6.7).
     */
    void change(EntityDefinition entity, long id, String action, JsonNode params);

    /** The records of one entity, for the registry. */
    default EntityRecords of(EntityDefinition entity) {
        EntityRecordStore store = this;
        return new EntityRecords() {
            @Override
            public String entity() {
                return entity.code();
            }

            @Override
            public void requireVisible(long id) {
                store.requireVisible(entity, id);
            }

            @Override
            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search) {
                return store.page(entity, limit, cursor, filter, sort, search);
            }

            @Override
            public void delete(long id) {
                store.delete(entity, id);
            }

            @Override
            public void archive(long id) {
                store.archive(entity, id);
            }

            @Override
            public void change(long id, String action, JsonNode params) {
                store.change(entity, id, action, params);
            }
        };
    }
}
