package com.smartup24.cms.instance.support.entity;

import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The module boundaries of a declaration's own SQL (ADR-0026; ADR-0032, 6.11 and 12): the relations an entity names —
 * its table, the tables of its collections and links, and the relations its expressions, computed fields and custom
 * scope read — are its module's own or another module's published views ({@code <owner>_pub_*}, read only). A custom
 * scope may also read the data-scope relations of {@code md}, which owns the scope rules (ADR-0013). Used by the
 * contract kit for every entity, a module's outside the monorepo included, and by {@code EntitySqlBoundariesTest} with
 * the exact owners of the built-in tables.
 */
public final class EntitySqlBoundaries {

    /** A published read view of a module: {@code <owner prefix>_pub_<name>} (ADR-0026). */
    public static final Pattern PUBLISHED_VIEW = Pattern.compile("^[a-z][a-z0-9_]*?_pub_[a-z0-9_]+$");

    /** The prefix of the data-scope relations a custom scope may read besides its module's (ADR-0013). */
    public static final String SCOPE_OWNER_PREFIX = "md_";

    /**
     * A relation after {@code from} or {@code join}, not a column ({@code t.x}) or a function ({@code extract(...)}):
     * only names of relations the database has count, so {@code extract(year from created_at)} names none.
     */
    private static final Pattern RELATION =
            Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)\\b(?!\\s*[.(])");

    private EntitySqlBoundaries() {}

    /** A piece of SQL the declaration gives the runtime, where it comes from and whether it is the scope's. */
    public record Fragment(String source, String sql, boolean scope) {}

    /**
     * Every SQL fragment of the declaration: the tables it names, the expressions and computed fields of the record and
     * of its collections' rows, and what its custom scope answers for each of {@code viewers} (the fragment depends on
     * the viewer's rule).
     */
    public static List<Fragment> fragments(EntityDefinition entity, List<Long> viewers) {
        EntityModel model = Objects.requireNonNull(entity.model(), entity.code());
        List<Fragment> fragments = new ArrayList<>();
        fragments.add(new Fragment("table", "from " + model.table(), false));
        fields(model.fields(), "field ", fragments);
        for (EntityCollection collection : model.collections()) {
            fragments.add(new Fragment("collection " + collection.key(), "from " + collection.table(), false));
            fields(collection.fields(), "field " + collection.key() + ".", fragments);
        }
        if (model.scope() instanceof EntityScope.Custom custom) {
            Set<String> seen = new LinkedHashSet<>();
            for (long viewer : viewers) {
                String sql = custom.provider().filter(viewer, model.alias()).sql();
                if (seen.add(sql)) fragments.add(new Fragment("scope " + custom.name(), sql, true));
            }
        }
        return fragments;
    }

    private static void fields(List<EntityField> fields, String prefix, List<Fragment> fragments) {
        for (EntityField field : fields) {
            switch (field.source()) {
                case FieldSource.Expression expression ->
                    fragments.add(new Fragment(prefix + field.key(), expression.sql(), false));
                case FieldSource.Computed computed ->
                    fragments.add(new Fragment(prefix + field.key(), computed.sql(), false));
                case FieldSource.Link link ->
                    fragments.add(new Fragment(prefix + field.key(), "from " + link.table(), false));
                default -> {
                    // A column, money columns, an attribute or a system value names no relation.
                }
            }
        }
    }

    /** The names after {@code from} and {@code join} in the fragment, lower-cased. */
    public static Set<String> named(String sql) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = RELATION.matcher(sql);
        while (matcher.find()) names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return names;
    }

    /**
     * The relations of the database a fragment names that its module may not read: neither {@code owned} nor a
     * published view, nor — in a scope — a data-scope relation of {@code md}. Each reads
     * {@code <entity>: <source> reads <relation>}.
     */
    public static List<String> violations(
            EntityDefinition entity, List<Fragment> fragments, Set<String> relations, Predicate<String> owned) {
        Set<String> found = new TreeSet<>();
        for (Fragment fragment : fragments) {
            for (String name : named(fragment.sql())) {
                if (!relations.contains(name)
                        || owned.test(name)
                        || PUBLISHED_VIEW.matcher(name).matches()) continue;
                if (fragment.scope() && name.startsWith(SCOPE_OWNER_PREFIX)) continue;
                found.add(entity.code() + ": " + fragment.source() + " reads " + name);
            }
        }
        return List.copyOf(found);
    }

    /** The tables and views of the database's current schema. */
    public static Set<String> relations(JdbcClient jdbc) {
        return new TreeSet<>(jdbc.sql("""
                        select table_name from information_schema.tables where table_schema = current_schema()
                        """).query(String.class).list());
    }

    /**
     * The relations a module owns when nothing more exact is known (a module outside the monorepo): those that share
     * the prefix of the entity's table up to its first underscore ({@code library_} of {@code library_books}).
     */
    public static Predicate<String> tablePrefixOf(EntityDefinition entity) {
        String table = Objects.requireNonNull(entity.model(), entity.code()).table();
        int cut = table.indexOf('_');
        String prefix = cut < 0 ? table + "_" : table.substring(0, cut + 1);
        return name -> name.equals(table) || name.startsWith(prefix);
    }
}
