package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.EntityEnums.Items;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.ConditionItemMeta;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.DefaultValueMeta;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryRef;
import com.smartup24.cms.instance.common.security.SecurityContext;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
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

    private final EntityRegistry registry;
    private final Function<String, Items> enumItems;

    @Autowired
    public FormMetaController(EntityRegistry registry, EntityEnums enums) {
        this.registry = registry;
        this.enumItems = enums::all;
    }

    /** A controller without reference entities: an enumeration offers no items. */
    public FormMetaController(EntityRegistry registry) {
        this.registry = registry;
        this.enumItems = code -> Items.NONE;
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
     * archived ones too, so an old value is named — in {@code optionLabels}.
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
            @Nullable String jsonRoot) {}

    public record FormSectionMeta(String key, String labelKey, List<String> fields) {}

    public record FormMeta(
            String code,
            @Nullable String listCode,
            List<FormFieldMeta> fields,
            List<FormSectionMeta> layout,
            List<String> actions,
            List<String> capabilities) {}

    /** Anyone signed in may ask; the entity's own right is checked below. A string: common depends on no module. */
    @Operation(
            summary = "Get a form description",
            description =
                    "The fields, layout and rules of an entity form, built from its declaration; the entity's own right is checked.")
    @GetMapping("/{code}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<FormMeta> get(@PathVariable String code) {
        EntityDefinition entity = registry.find(code)
                .filter(found -> SecurityContext.hasPermission(found.form(), "view"))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.entity_not_found"));
        return ResponseEntity.ok(of(entity, enumItems));
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
        Set<String> hidden = EntityFieldRights.hidden(entity);
        Set<String> readonly = EntityFieldRights.readonly(entity);
        return new FormMeta(
                entity.code(),
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
                entity.actions().stream()
                        .filter(action -> SecurityContext.hasPermission(entity.form(), action.permission()))
                        .map(EntityDefinition.EntityAction::code)
                        .toList(),
                entity.capabilities().stream()
                        .map(EntityCapability::wire)
                        .sorted()
                        .toList());
    }
}
