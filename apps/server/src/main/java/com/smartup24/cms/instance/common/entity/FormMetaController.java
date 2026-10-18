package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.EntityEnums.Items;
import com.smartup24.cms.instance.common.entity.FormDocumentMetas.FormCollectionMeta;
import com.smartup24.cms.instance.common.entity.FormDocumentMetas.FormTabMeta;
import com.smartup24.cms.instance.common.entity.FormDocumentMetas.FormWorkflowMeta;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.ConditionItemMeta;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.DefaultValueMeta;
import com.smartup24.cms.instance.common.entity.FormLogicMetas.FormRuleMeta;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.formula.FormulaNode;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/form-meta/{code}} (ADR-0019, 2.2): an entity's form fields, layout and rules, and the
 * actions the viewer may take — the screen draws its buttons from them, not from checks of its own. An unknown
 * entity and one the viewer may not see answer the same 404, so the answer does not tell which exist.
 */
@RestController
@RequestMapping("/api/v1/form-meta")
public class FormMetaController {

    private final EntityGate gate;
    private final Function<String, Items> enumItems;
    private final Supplier<List<EntityTitles>> titles;
    private final Function<String, EntityLogic> logic;
    private final Function<String, @Nullable EntityProcessView> process;

    /** The entity opens through {@link EntityGate}: its module switched on and its {@code view} right held. */
    @Autowired
    public FormMetaController(
            EntityGate gate,
            EntityEnums enums,
            ObjectProvider<EntityTitles> titles,
            ObjectProvider<LiveEntities> live) {
        this.gate = gate;
        this.enumItems = enums::all;
        this.titles = () -> titles.orderedStream().toList();
        LiveEntities served = live.getIfAvailable();
        this.logic = served == null ? code -> EntityLogic.NONE : served::logic;
        this.process =
                served == null ? code -> null : code -> served.process(code).orElse(null);
    }

    /** A controller of the declarations alone, without reference entities: an enumeration offers no items. */
    public FormMetaController(EntityRegistry registry) {
        this.gate = EntityGate.of(registry);
        this.enumItems = code -> Items.NONE;
        this.titles = List::of;
        this.logic = code -> EntityLogic.NONE;
        this.process = code -> null;
    }

    /**
     * A form field as the client sees it. Named apart from the list field of {@code query-meta}, so the API
     * description keeps the two schemas and the web types know {@code required}, the lengths and the options
     * (ADR-0032, 3.3). A field is read-only when its declaration says so or when the viewer lacks its
     * {@code readonlyUnless} right (ADR-0032, 4.4 and 5.2): {@code readonly} — whatever the record's state (no right,
     * {@code readonly(ALWAYS)}, a computed value); {@code readonlyOnUpdate} — once the record exists;
     * {@code readonlyWhen} — on an existing record while the condition holds. The flags and parameters of plan 10/10,
     * item 5.2 (ADR-0032, 4.1–4.5) are given only when a field has them: the two conditional read-only kinds,
     * {@code computed}, {@code defaultValue}, {@code visibleWhen}, the scale, item count, file size and types,
     * currencies and JSON root; an enumeration's active items come as {@code options}, the names of all its items —
     * archived ones too, so an old value is named — in {@code optionLabels}. Money in the currency of a select field
     * names it in {@code currencyFrom} (ADR-0032, 9.1): a field of the record, or of the document for a row.
     */
    public record FormFieldMeta(
            String key,
            String labelKey,
            @Nullable String label,
            String type,
            boolean required,
            boolean readonly,
            @Nullable Integer minLength,
            @Nullable Integer maxLength,
            @Nullable BigDecimal min,
            @Nullable BigDecimal max,
            @Nullable String pattern,
            List<String> options,
            @Nullable String optionLabelPrefix,
            @Nullable QueryRef ref,
            @Nullable String attribute,
            @Nullable Map<String, String> optionLabels,
            @Nullable Boolean readonlyOnUpdate,
            @Nullable List<ConditionItemMeta> readonlyWhen,
            @Nullable Boolean computed,
            @Nullable DefaultValueMeta defaultValue,
            @Nullable List<ConditionItemMeta> visibleWhen,
            @Nullable Integer scale,
            @Nullable Integer maxItems,
            @Nullable Long maxBytes,
            @Nullable List<String> contentTypes,
            @Nullable List<String> currencies,
            @Nullable String jsonRoot,
            @Nullable String currencyFrom,
            @Nullable FormulaNode formula,
            @Nullable FormulaNode defaultFormula,
            @Nullable FormulaNode visibleIf,
            @Nullable FormulaNode requiredIf,
            @Nullable FormulaNode readonlyIf) {}

    public record FormSectionMeta(String key, String labelKey, List<String> fields) {}

    /**
     * An entity's form. A document also gives its collections with the fields of a row, its process and the tabs of
     * its card (ADR-0032, 9; plan 10/10, item 5.7); an entity without them answers without these properties.
     * {@code titleKey} is the dictionary key of the entity's name (ADR-0031): its menu label, else the name of its
     * right, so a screen never names the entity by its code. {@code titleField} is the key of the field that names a
     * record (ADR-0041) when the entity says one and the viewer sees that field; without it a screen names a record by
     * the first filled text field of the layout. {@code rules} are the validation rules of an entity whose form logic
     * is written in formulas (ADR-0042); the fields of such a form carry their own formulas.
     */
    public record FormMeta(
            String code,
            @Nullable String titleKey,
            @Nullable String listCode,
            List<FormFieldMeta> fields,
            List<FormSectionMeta> layout,
            List<String> actions,
            List<String> capabilities,
            @Nullable List<FormCollectionMeta> collections,
            @Nullable FormWorkflowMeta workflow,
            @Nullable List<FormTabMeta> tabs,
            @Nullable String titleField,
            @Nullable List<FormRuleMeta> rules) {}

    /** Anyone signed in may ask; the entity's own right is checked below. A string: common depends on no module. */
    @Operation(
            summary = "Get a form description",
            description =
                    "The fields, layout and rules of an entity form, built from its declaration; the entity's own right is checked.")
    @GetMapping("/{code}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<FormMeta> get(@PathVariable String code) {
        EntityDefinition entity = gate.findVisible(code).orElseThrow(EntityGate::unknownEntity);
        // A related list of the card only of an entity the viewer may see (ADR-0032, 9.3).
        FormMeta meta = titled(of(entity, enumItems, gate::visible), titleField(entity));
        meta = FormLogicMetas.with(meta, logic.apply(entity.code()));
        meta = withProcess(meta, process.apply(entity.code()));
        return ResponseEntity.ok(gate.writable(entity) ? meta : readOnly(meta));
    }

    /** The form of an entity whose records a lock holds read-only (ADR-0041): it offers no action. */
    static FormMeta readOnly(FormMeta meta) {
        return new FormMeta(
                meta.code(),
                meta.titleKey(),
                meta.listCode(),
                meta.fields(),
                meta.layout(),
                List.of(),
                meta.capabilities(),
                meta.collections(),
                meta.workflow(),
                meta.tabs(),
                meta.titleField(),
                meta.rules());
    }

    /** The form whose process says what a live form adds to it (ADR-0043); the form as it is without that. */
    static FormMeta withProcess(FormMeta meta, @Nullable EntityProcessView view) {
        if (view == null || meta.workflow() == null) return meta;
        return new FormMeta(
                meta.code(),
                meta.titleKey(),
                meta.listCode(),
                meta.fields(),
                meta.layout(),
                meta.actions(),
                meta.capabilities(),
                meta.collections(),
                FormDocumentMetas.withView(meta.workflow(), view),
                meta.tabs(),
                meta.titleField(),
                meta.rules());
    }

    /** The form naming its records by {@code field}, when the viewer sees that field. */
    static FormMeta titled(FormMeta meta, @Nullable String field) {
        if (field == null
                || meta.fields().stream().noneMatch(candidate -> candidate.key().equals(field))) return meta;
        return new FormMeta(
                meta.code(),
                meta.titleKey(),
                meta.listCode(),
                meta.fields(),
                meta.layout(),
                meta.actions(),
                meta.capabilities(),
                meta.collections(),
                meta.workflow(),
                meta.tabs(),
                field,
                meta.rules());
    }

    private @Nullable String titleField(EntityDefinition entity) {
        for (EntityTitles source : titles.get()) {
            String field = source.titleField(entity);
            if (field != null) return field;
        }
        return null;
    }

    /** The form as the viewer may use it, for an entity without enumerations. */
    static FormMeta of(EntityDefinition entity) {
        return of(entity, code -> Items.NONE);
    }

    /**
     * The form as the viewer may use it (ADR-0032, 5.2): a field whose {@code requires} they lack is absent, from its
     * section too, and a section left empty goes; a field whose {@code readonlyUnless} they lack is read-only.
     */
    static FormMeta of(EntityDefinition entity, Function<String, Items> enumItems) {
        return of(entity, enumItems, code -> false);
    }

    /** The form as the viewer may use it; a related list of the card only of an entity {@code viewable} accepts. */
    static FormMeta of(EntityDefinition entity, Function<String, Items> enumItems, Predicate<String> viewable) {
        Set<String> hidden = EntityFieldRights.hidden(entity);
        Set<String> readonly = EntityFieldRights.readonly(entity);
        return new FormMeta(
                entity.code(),
                titleKey(entity),
                entity.listCode(),
                entity.fields().stream()
                        .filter(field -> !hidden.contains(field.key()))
                        .map(field -> FormFieldMetas.of(field, enumItems, readonly.contains(field.key())))
                        .toList(),
                entity.layout().stream()
                        .map(s -> new FormSectionMeta(
                                s.key(),
                                s.labelKey(),
                                s.fields().stream()
                                        .filter(key -> !hidden.contains(key))
                                        .toList()))
                        .filter(section -> !section.fields().isEmpty())
                        .toList(),
                actions(entity),
                entity.capabilities().stream()
                        .map(EntityCapability::wire)
                        .sorted()
                        .toList(),
                FormDocumentMetas.collections(entity, enumItems),
                FormDocumentMetas.workflow(entity),
                FormDocumentMetas.tabs(entity, viewable),
                null,
                null);
    }

    /** The dictionary key of the entity's name: its menu label, else the name of its right; null without either. */
    public static @Nullable String titleKey(EntityDefinition entity) {
        if (entity.menu() != null) return entity.menu().labelKey();
        return entity.rights() != null ? entity.rights().nameKey() : null;
    }

    /**
     * The actions the viewer may take on the entity, in declaration order, and {@code import} last when the entity
     * declares IMPORT and the viewer holds its right (ADR-0032, 10.1): the list's toolbar offers the import by it.
     */
    private static List<String> actions(EntityDefinition entity) {
        List<String> actions = new ArrayList<>(entity.actions().stream()
                .filter(action -> SecurityContext.hasPermission(entity.form(), action.permission()))
                .map(EntityDefinition.EntityAction::code)
                .toList());
        if (entity.capabilities().contains(EntityCapability.IMPORT)
                && SecurityContext.hasPermission(entity.form(), EntityDefinition.IMPORT)) {
            actions.add(EntityDefinition.IMPORT);
        }
        return actions;
    }
}
