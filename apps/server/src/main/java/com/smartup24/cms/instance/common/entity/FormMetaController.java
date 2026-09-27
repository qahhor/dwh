package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryRef;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.math.BigDecimal;
import java.util.List;
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

    public FormMetaController(EntityRegistry registry) {
        this.registry = registry;
    }

    public record FieldMeta(
            String key,
            String labelKey,
            String label,
            String type,
            boolean required,
            Integer minLength,
            Integer maxLength,
            BigDecimal min,
            BigDecimal max,
            String pattern,
            List<String> options,
            String optionLabelPrefix,
            QueryRef ref,
            String attribute) {

        static FieldMeta of(FormField field) {
            return new FieldMeta(
                    field.key(),
                    field.labelKey(),
                    field.label(),
                    field.type().wire(),
                    field.required(),
                    field.minLength(),
                    field.maxLength(),
                    field.min(),
                    field.max(),
                    field.pattern(),
                    field.options(),
                    field.optionLabelPrefix(),
                    field.ref(),
                    field.attribute());
        }
    }

    public record SectionMeta(String key, String labelKey, List<String> fields) {}

    public record FormMeta(
            String code,
            String listCode,
            List<FieldMeta> fields,
            List<SectionMeta> layout,
            List<String> actions,
            List<String> capabilities) {}

    /** Anyone signed in may ask; the entity's own right is checked below. A string: common depends on no module. */
    @GetMapping("/{code}")
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<FormMeta> get(@PathVariable String code) {
        EntityDefinition entity = registry.find(code)
                .filter(found -> SecurityContext.hasPermission(found.form(), "view"))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "ENTITY_NOT_FOUND"));
        return ResponseEntity.ok(of(entity));
    }

    static FormMeta of(EntityDefinition entity) {
        return new FormMeta(
                entity.code(),
                entity.listCode(),
                entity.fields().stream().map(FieldMeta::of).toList(),
                entity.layout().stream()
                        .map(s -> new SectionMeta(s.key(), s.labelKey(), s.fields()))
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
