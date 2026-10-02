package com.smartup24.cms.instance.search.repository;

import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.security.ScopeKeys;
import com.smartup24.cms.instance.search.service.SearchEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The SQL of an entity's search documents, built from its declaration (ADR-0032, 10.3; plan 10/10, item 5.8) — no
 * projection written by hand for an entity. Every identifier comes from the declaration, checked when it was built
 * (ADR-0032, 12); values are parameters. A document holds the record's id, the searched fields under their keys as
 * text and the keys of the entity's scope: {@code scope_users} — the users that see the record under the rule
 * {@code SELF} (its owner, its participants), {@code scope_units} — the org units that open it under
 * {@code SUBTREE}/{@code UNITS}. A record the search does not find — archived, or failing the spec's condition — has no
 * document.
 */
public final class SearchDocumentSql {

    /** The document property of the record's id as a number; {@code id} is its text, the document's own id. */
    public static final String RECORD_ID = "record_id";

    /** The users whose own rule ({@code SELF}) sees the record. */
    public static final String SCOPE_USERS = "scope_users";

    /** The org units whose viewers ({@code SUBTREE}/{@code UNITS}) see the record. */
    public static final String SCOPE_UNITS = "scope_units";

    private static final String NO_KEYS = "array[]::bigint[]";

    private SearchDocumentSql() {}

    /**
     * The document of the record {@code v.entity_id} as {@code document} (jsonb), for a lateral join over the
     * projection versions; no row when the search does not find the record.
     */
    public static String document(SearchEntity entity) {
        EntityModel model = entity.model();
        String alias = model.alias();
        List<String> pairs = new ArrayList<>();
        pairs.add("'id', " + alias + ".id::text");
        pairs.add("'" + RECORD_ID + "', " + alias + ".id");
        for (EntityField field : entity.fields()) {
            pairs.add("'" + field.key() + "', coalesce((" + field.sql(alias) + ")::text, '')");
        }
        pairs.add("'" + SCOPE_USERS + "', to_jsonb(" + distinct(users(entity)) + ")");
        pairs.add("'" + SCOPE_UNITS + "', to_jsonb(" + distinct(units(entity)) + ")");
        return "select jsonb_build_object(" + String.join(", ", pairs) + ") as document from " + from(model) + " where "
                + alias + ".id = v.entity_id" + found(entity);
    }

    /** The ids of the records the search finds after {@code :after}, at most {@code :limit}, in order. */
    public static String ids(SearchEntity entity) {
        String alias = entity.model().alias();
        return "select " + alias + ".id from " + from(entity.model()) + " where " + alias + ".id > :after"
                + found(entity) + " order by " + alias + ".id limit :limit";
    }

    /** How many records the search finds. */
    public static String count(SearchEntity entity) {
        return "select count(*) from " + from(entity.model()) + " where 1=1" + found(entity);
    }

    /** The ids among {@code :ids} of records the search finds that the caller's scope predicate keeps. */
    public static String visible(SearchEntity entity, String scope) {
        String alias = entity.model().alias();
        return "select " + alias + ".id from " + from(entity.model()) + " where " + alias + ".id = any(:ids)"
                + found(entity) + scope;
    }

    /**
     * The records the caller's scope keeps whose searched fields match one of {@code :patterns}, a title matching the
     * query {@code :primary} first, at most {@code :limit}: the search over PostgreSQL when Typesense is off.
     */
    public static String matching(SearchEntity entity, String scope) {
        EntityModel model = entity.model();
        String alias = model.alias();
        List<String> matches = entity.fields().stream()
                .map(field -> "(" + field.sql(alias) + ")::text ilike any(:patterns)")
                .toList();
        return select(entity) + " where 1=1" + found(entity) + scope + " and (" + String.join(" or ", matches) + ")"
                + " order by ((" + entity.titleField().sql(alias) + ")::text ilike :primary) desc, " + alias
                + ".id desc limit :limit";
    }

    /** The record {@code :id}, when the search finds it and the caller's scope keeps it. */
    public static String exact(SearchEntity entity, String scope) {
        return select(entity) + " where " + entity.model().alias() + ".id = :id" + found(entity) + scope;
    }

    /** The users of the record's scope keys ({@code bigint[]}): its owner, or the participants a custom scope names. */
    static String users(SearchEntity entity) {
        EntityModel model = entity.model();
        String alias = model.alias();
        return switch (model.scope()) {
            case EntityScope.Owner owner -> "array[" + alias + "." + owner.ownerColumn() + "]";
            case EntityScope.OrgUnit unit -> "array[" + alias + "." + unit.ownerColumn() + "]";
            case EntityScope.All _ -> NO_KEYS;
            case EntityScope.Custom _ ->
                "(" + Objects.requireNonNull(entity.spec().scopeUsers()) + ")::bigint[]";
        };
    }

    /** The org units of the record's scope keys ({@code bigint[]}): its unit, or the units of its participants. */
    static String units(SearchEntity entity) {
        EntityModel model = entity.model();
        String alias = model.alias();
        return switch (model.scope()) {
            case EntityScope.Owner _, EntityScope.All _ -> NO_KEYS;
            case EntityScope.OrgUnit unit -> "array[" + alias + "." + unit.orgUnitColumn() + "]";
            case EntityScope.Custom _ -> ScopeKeys.unitsOf(users(entity));
        };
    }

    /** The id, the title and the searched fields of a record, for a hit read from PostgreSQL. */
    private static String select(SearchEntity entity) {
        EntityModel model = entity.model();
        String alias = model.alias();
        List<String> columns = new ArrayList<>();
        columns.add(alias + ".id as \"" + RECORD_ID + "\"");
        for (EntityField field : entity.fields()) {
            columns.add("(" + field.sql(alias) + ")::text as \"" + field.key() + "\"");
        }
        return "select " + String.join(", ", columns) + " from " + from(model);
    }

    /** What makes the search find a record: in use, not archived, and the spec's condition. */
    private static String found(SearchEntity entity) {
        String alias = entity.model().alias();
        StringBuilder condition = new StringBuilder();
        if (entity.archivable()) condition.append(" and ").append(alias).append(".archived_at is null");
        String when = entity.spec().indexedWhen();
        if (when != null) condition.append(" and (").append(when).append(')');
        return condition.toString();
    }

    private static String distinct(String keys) {
        return "array(select distinct scope_key from unnest(" + keys + ") scope_key where scope_key is not null"
                + " order by scope_key)";
    }

    private static String from(EntityModel model) {
        return model.table() + " " + model.alias();
    }
}
