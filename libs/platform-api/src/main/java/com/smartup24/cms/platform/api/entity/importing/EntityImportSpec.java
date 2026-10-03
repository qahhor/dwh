package com.smartup24.cms.platform.api.entity.importing;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldAccess;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * How an entity's records are imported (ADR-0032, 10.1): the key of the upsert — a row whose key names a record in the
 * importer's scope changes it, any other row creates one. The key is a text field of the form kept in a column of its
 * own that a unique index guards ({@code EntityImportDeclaredTest} checks the index) and open to everyone who sees the
 * entity, so the template always holds it. A key the server gives (a number from a sequence) is read-only: a row finds
 * a record by it, and a row without it creates a record that gets its own.
 *
 * @param key the key of the field the upsert finds a record by ({@code code})
 */
@PlatformApi(since = "1.0", stability = Stability.EXPERIMENTAL)
public record EntityImportSpec(String key) {

    /** The kinds of field a key can be: a text a person types and the file keeps as it is. */
    private static final Set<FieldType> KEY_TYPES = Set.of(FieldType.TEXT, FieldType.EMAIL, FieldType.PHONE);

    public EntityImportSpec {
        Objects.requireNonNull(key, "key");
    }

    /** Refuses a key that is not such a field of {@code table}. */
    public void check(String table, List<EntityField> fields) {
        EntityField field = fields.stream()
                .filter(declared -> declared.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Table " + table + ": no import key field " + key));
        boolean column = field.source() instanceof FieldSource.Column;
        boolean form = field.form() != null;
        if (!column || !form || !KEY_TYPES.contains(field.type()) || !FieldAccess.OPEN.equals(field.access())) {
            throw new IllegalArgumentException("Table " + table + ": the import key " + key
                    + " is a text field of the form in a column of its own, open to every viewer (ADR-0032, 10.1)");
        }
    }
}
