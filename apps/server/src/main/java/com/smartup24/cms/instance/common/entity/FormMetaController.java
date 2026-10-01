package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.ConditionItemMeta;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.DefaultValueMeta;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryRef;
import com.smartup24.cms.instance.common.security.SecurityContext;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
    private final Function<String, Map<String, String>> enumItems;

    @Autowired
    public FormMetaController(EntityRegistry registry, EntityEnums enums) {
        this.registry = registry;
        this.enumItems = enums::items;
    }

    /** A controller without reference entities: an enumeration offers no items. */
    public FormMetaController(EntityRegistry registry) {
        this.registry = registry;
        this.enumItems = code -> Map.of();
    }

    /**
     * A form field as the client sees it. Named apart from the list field of {@code query-meta}, so the API
     * description keeps the two schemas and the web types know {@code required}, the lengths and the options
     * (ADR-0032, 3.3). The flags and parameters of plan 10/10, item 5.2 (ADR-0032, 4.1–4.5) are given only when a
     * field has them: {@code readonly} ({@code always}, {@code on_update}, {@code when} with {@code readonlyWhen}),
     * {@code computed}, {@code defaultValue}, {@code visibleWhen}, the scale, item count, file size and types,
     * currencies and JSON root; an enumeration's items come as {@code options} with their names in
     * {@code optionLabels}.
     */
    public record FormFieldMeta(
            String key,
            String labelKey,
            @Nullable String label,
            String type,
            boolean required,
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
            @Nullable String readonly,
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

    static FormMeta of(EntityDefinition entity, Function<String, Map<String, String>> enumItems) {
        return new FormMeta(
                entity.code(),
                entity.listCode(),
                entity.fields().stream()
                        .map(field -> FormFieldMetas.of(field, enumItems))
                        .toList(),
                entity.layout().stream()
                        .map(s -> new FormSectionMeta(s.key(), s.labelKey(), s.fields()))
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
