package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * List metadata for the client: fields, their types and filter operations, sorting and page size.
 * Served to whoever may view the list itself; an unknown code and a forbidden list look the same (404),
 * so responses cannot be used to enumerate which lists exist.
 */
@RestController
@RequestMapping("/api/v1/query-meta")
public class QueryMetaController {

    private final QueryListRegistry registry;

    public QueryMetaController(QueryListRegistry registry) {
        this.registry = registry;
    }

    /**
     * A list field as the client sees it; {@code format} names the entity field type when the list type alone does not
     * say how to show the value, {@code enumLabels} the words of an enumeration read from its reference entity
     * (ADR-0032, 4.1 and 4.5).
     */
    public record FieldMeta(
            String key,
            String labelKey,
            String type,
            List<String> ops,
            boolean sortable,
            boolean nullable,
            boolean defaultVisible,
            List<String> enumValues,
            @Nullable String enumLabelPrefix,
            boolean searchable,
            @Nullable String label,
            @Nullable String attribute,
            @Nullable QueryRef ref,
            @Nullable String format,
            @Nullable Map<String, String> enumLabels) {

        static FieldMeta of(QueryField field) {
            List<String> ops = field.ops().stream().map(QueryOp::wire).toList();
            return new FieldMeta(
                    field.key(),
                    field.labelKey(),
                    field.type().wire(),
                    ops,
                    field.sortable(),
                    field.nullable(),
                    field.defaultVisible(),
                    field.enumValues(),
                    field.enumLabelPrefix(),
                    field.searchable(),
                    field.label(),
                    field.attribute(),
                    field.ref(),
                    field.format(),
                    field.enumLabels());
        }
    }

    public record ListMeta(
            String code,
            List<FieldMeta> fields,
            String defaultSort,
            int defaultLimit,
            int maxLimit,
            int maxConditions,
            int maxInValues) {}

    /**
     * Any signed-in user (every role has {@code md.profile:view}); the permission for the list itself
     * is checked below against the registry. A string, not {@code MdPref}: {@code common} does not depend on modules.
     */
    @Operation(
            summary = "Get a list description",
            description =
                    "The fields, filters, sorts and defaults of a registry list, including custom fields; the list's own right is checked.")
    @GetMapping("/{code}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<ListMeta> get(@PathVariable String code) {
        QueryList list = registry.find(code)
                .filter(found -> SecurityContext.hasPermission(found.form(), found.action()))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.query_list_not_found"));
        return ResponseEntity.ok(new ListMeta(
                list.code(),
                list.viewerFields().stream().map(FieldMeta::of).toList(),
                (list.defaultDescending() ? "-" : "") + list.defaultSort(),
                list.defaultLimit(),
                list.maxLimit(),
                QueryCompiler.MAX_CONDITIONS,
                QueryCompiler.MAX_IN_VALUES));
    }
}
