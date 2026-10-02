package com.smartup24.cms.instance.common.entity.store;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Finds the record an import row names by the entity's import key (ADR-0032, 10.1): only in the importer's scope — a
 * record outside it is not found, as a read by id does not find it (ADR-0013) — archived ones too, since the key is
 * unique (among the records in use at least; one in use comes first). The SQL is built from the declaration, the key's value is a parameter.
 */
@Repository
public class EntityKeyLookup {

    private final JdbcClient jdbc;

    public EntityKeyLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The id of the record whose import key is {@code value}, in {@code scope}. */
    public Optional<Long> find(EntityDefinition entity, QueryList list, String value, QueryPlan.SqlFragment scope) {
        EntityModel model = Objects.requireNonNull(entity.model(), entity.code());
        String key = Objects.requireNonNull(model.importing(), entity.code()).key();
        Map<String, Object> params = new LinkedHashMap<>(scope.params());
        params.put("importKey", value);
        String sql = "select " + model.alias() + ".id from " + list.from() + " where " + model.sqlOf(key)
                + " = :importKey" + scope.sql() + order(entity, model) + " limit 1";
        return jdbc.sql(sql).params(params).query(Long.class).optional();
    }

    /** A record in use before an archived one, when a key is unique only among the records in use. */
    private static String order(EntityDefinition entity, EntityModel model) {
        return entity.capabilities().contains(EntityCapability.ARCHIVE)
                ? " order by " + model.alias() + ".archived_at is not null, " + model.alias() + ".id"
                : "";
    }
}
