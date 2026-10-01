package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Which rows of an entity a viewer sees (ADR-0032, 5.1; ADR-0013). Every entity with a table declares one —
 * {@link Entity#build()} refuses an entity without it — and {@link EntityScopes} turns it into the predicate of its
 * list, its count, its export and every read by id, so a record outside the scope answers as a missing one.
 */
public sealed interface EntityScope {

    /** What the scope reads, for the review list of {@code EntityScopeDeclaredTest}: {@code owner(created_by)}. */
    String describe();

    /** The columns of the entity's table the scope reads; the entity's table must have them. */
    default List<String> columns() {
        return List.of();
    }

    /** A personal record: only its owner sees it, whatever the role's rule; the owner is its author. */
    static EntityScope owner(String ownerColumn) {
        return new Owner(ownerColumn);
    }

    /**
     * A record of an org unit, seen by the role's rule (ADR-0013): {@code ALL} — every record, {@code SUBTREE}/
     * {@code UNITS} — a unit of the viewer's scope, {@code SELF} — the viewer's own records.
     */
    static EntityScope orgUnit(String orgUnitColumn, String ownerColumn) {
        return new OrgUnit(orgUnitColumn, ownerColumn);
    }

    /** Reference data and settings: every row is seen by whoever holds the entity's right. */
    static EntityScope all() {
        return All.INSTANCE;
    }

    /** A module's own rule ({@code tasks} by participation), named for the review list. */
    static EntityScope custom(String name, ScopeProvider provider) {
        return new Custom(name, provider);
    }

    /** {@link #owner(String)}. */
    record Owner(String ownerColumn) implements EntityScope {
        public Owner {
            requireColumn(ownerColumn);
        }

        @Override
        public String describe() {
            return "owner(" + ownerColumn + ")";
        }

        @Override
        public List<String> columns() {
            return List.of(ownerColumn);
        }
    }

    /** {@link #orgUnit(String, String)}. */
    record OrgUnit(String orgUnitColumn, String ownerColumn) implements EntityScope {
        public OrgUnit {
            requireColumn(orgUnitColumn);
            requireColumn(ownerColumn);
        }

        @Override
        public String describe() {
            return "orgUnit(" + orgUnitColumn + ", " + ownerColumn + ")";
        }

        @Override
        public List<String> columns() {
            return List.of(orgUnitColumn, ownerColumn);
        }
    }

    /** {@link #all()}. */
    enum All implements EntityScope {
        INSTANCE;

        @Override
        public String describe() {
            return "all";
        }
    }

    /** {@link #custom(String, ScopeProvider)}. */
    record Custom(String name, ScopeProvider provider) implements EntityScope {

        private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

        public Custom {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Bad custom scope name: " + name);
            }
            Objects.requireNonNull(provider, "provider");
        }

        @Override
        public String describe() {
            return "custom(" + name + ")";
        }
    }

    /**
     * A module's row rule over the entity's table, aliased as its declaration says ({@code t} for tasks): the
     * fragment starts with {@code and}, an empty one restricts nothing.
     */
    @FunctionalInterface
    interface ScopeProvider {
        ScopeFilter filter(long userId, String alias);
    }

    private static void requireColumn(String column) {
        if (column == null || !FieldSource.IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("Bad scope column: " + column);
        }
    }
}
