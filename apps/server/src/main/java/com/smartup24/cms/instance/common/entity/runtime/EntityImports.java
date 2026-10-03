package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldReadonly;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.FormPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * The import of entity records on the general runtime (ADR-0032, 10.1; plan 10/10, item 5.8): who may import, the
 * columns of the template and the batches of rows. A row is a create or a change through {@link EntityWrites} — the
 * same steps 4–13 of ADR-0032, 6.3 as a save through the API, hooks, audit and events included — found by the
 * declared key in the importer's scope; each row in a savepoint of its own ({@link EntityImportBatch}).
 */
@Component
public class EntityImports implements EntityImporter {

    /** The kinds of field a cell cannot hold: a file is uploaded and a JSON value typed in a form. */
    private static final Set<FieldType> NOT_IN_A_CELL = Set.of(FieldType.FILE, FieldType.IMAGE, FieldType.JSON);

    private final EntityGate gate;
    private final EntityEnums enums;
    private final EntityImportBatch batches;

    public EntityImports(EntityGate gate, EntityEnums enums, EntityImportBatch batches) {
        this.gate = gate;
        this.enums = enums;
        this.batches = batches;
    }

    @Override
    public String importable(String code) {
        return entity(code).code();
    }

    @Override
    public Template template(String code) {
        return template(entity(code));
    }

    private Template template(EntityDefinition entity) {
        EntityModel model = Objects.requireNonNull(entity.model(), entity.code());
        List<Column> columns = new ArrayList<>();
        String key = Objects.requireNonNull(model.importing()).key();
        for (EntityField field : model.fields()) {
            if (field.key().equals(key) || importable(field)) columns.add(column(field));
        }
        return new Template(entity.code(), key, columns);
    }

    @Override
    public List<RowOutcome> run(
            String code,
            boolean apply,
            List<Row> rows,
            Function<String, String> text,
            Consumer<List<RowOutcome>> checkpoint) {
        EntityDefinition entity = entity(code);
        return batches.run(entity, template(entity), apply, rows, text, checkpoint);
    }

    /**
     * The entity, when the person may import into it: viewable (404 otherwise, as an unknown entity), declaring IMPORT
     * (404 as a missing action) and with the right's {@code import} (403).
     */
    private EntityDefinition entity(String code) {
        EntityDefinition entity = gate.viewable(code);
        if (!entity.capabilities().contains(EntityCapability.IMPORT)) {
            throw ApiException.notFound(
                    ErrorCode.NOT_FOUND,
                    "error.common.entity_action_not_found",
                    Map.of("action", EntityDefinition.IMPORT));
        }
        if (!SecurityContext.hasPermission(entity.form(), EntityDefinition.IMPORT)) {
            throw ApiException.permissionDenied(entity.form(), EntityDefinition.IMPORT);
        }
        return entity;
    }

    /**
     * Whether the person may fill the field from a file: a field the form writes that a cell can hold, that they see
     * and may change and that is not read-only for good. The key is a column whatever it is: a row finds its record by
     * it.
     */
    private static boolean importable(EntityField field) {
        FormPart form = field.form();
        return field.importable()
                && form != null
                && !NOT_IN_A_CELL.contains(field.type())
                && !FieldReadonly.ALWAYS.equals(form.readonly())
                && EntityFieldRights.writable(field.access());
    }

    private Column column(EntityField field) {
        FormPart form = Objects.requireNonNull(field.form());
        List<Option> options = new ArrayList<>();
        if (field.type() == FieldType.SELECT) {
            String prefix = field.options().optionLabelPrefix();
            field.options()
                    .options()
                    .forEach(code -> options.add(new Option(code, prefix == null ? null : prefix + code, null)));
        } else if (field.type() == FieldType.ENUM && field.options().enumeration() != null) {
            enums.items(Objects.requireNonNull(field.options().enumeration()))
                    .forEach((code, name) -> options.add(new Option(code, null, name)));
        }
        return new Column(
                field.key(),
                field.labelKey(),
                field.label(),
                field.type(),
                form.required() && form.defaultValue() == null,
                options);
    }

    /** The action a row needs: {@code create} for a new record, {@code update} for a found one, when declared and held. */
    static boolean allowed(EntityDefinition entity, String action) {
        return entity.action(action)
                .map(EntityAction::permission)
                .filter(permission -> SecurityContext.hasPermission(entity.form(), permission))
                .isPresent();
    }

    /** The problem of a row the person may not write: the right it needs, as a 403 names it. */
    static FieldErrorItem forbidden(String address, EntityDefinition entity, String action) {
        String permission = entity.action(action).map(EntityAction::permission).orElse(action);
        return FieldErrorItem.keyed(
                address,
                ErrorCode.PERMISSION_DENIED.getCode(),
                "error.permission_denied_action",
                Map.of("right", entity.form() + "." + permission));
    }
}
