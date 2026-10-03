package com.smartup24.cms.platform.api.entity;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which rows of an entity a viewer sees (ADR-0032, 5.1; ADR-0013). Every entity with a table declares one —
 * {@link Entity#build()} refuses an entity without it — and the platform turns it into the predicate of its list, its
 * count, its export and every read by id, so a record outside the scope answers as a missing one.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    enum All implements EntityScope {
        INSTANCE;

        @Override
        public String describe() {
            return "all";
        }
    }

    /** {@link #custom(String, ScopeProvider)}. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    @PlatformApi(since = "1.0", stability = Stability.EXPERIMENTAL)
    @FunctionalInterface
    interface ScopeProvider {
        Condition filter(long userId, String alias);
    }

    /**
     * The row rule a {@link ScopeProvider} answers (ADR-0013): a SQL fragment appended to {@code ... where 1=1}, so it
     * starts with {@code and}; empty restricts nothing. A fragment that names the viewer binds {@code :scopeUserId}.
     *
     * @param sql         the fragment, {@code ""} or {@code " and ..."}
     * @param bindsUserId whether the fragment uses {@code :scopeUserId}
     * @param userId      the viewer it binds, when it does
     */
    @PlatformApi(since = "1.0", stability = Stability.EXPERIMENTAL)
    record Condition(
            String sql, boolean bindsUserId, @Nullable Long userId) {

        private static final Condition UNRESTRICTED = new Condition("", false, null);

        public Condition {
            Objects.requireNonNull(sql, "sql");
            if (!sql.isEmpty() && !sql.stripLeading().startsWith("and ")) {
                throw new IllegalArgumentException("A scope condition starts with and: " + sql);
            }
            if (bindsUserId && userId == null) {
                throw new IllegalArgumentException("A scope condition that binds the viewer names the viewer");
            }
        }

        /** Every row: the query is unchanged. */
        public static Condition unrestricted() {
            return UNRESTRICTED;
        }

        /** A fragment over the viewer {@code userId}, bound as {@code :scopeUserId}. */
        public static Condition of(String sql, long userId) {
            return new Condition(sql, true, userId);
        }
    }

    private static void requireColumn(String column) {
        if (column == null || !FieldSource.IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("Bad scope column: " + column);
        }
    }
}
