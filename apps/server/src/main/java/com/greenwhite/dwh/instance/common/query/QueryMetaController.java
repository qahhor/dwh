package com.greenwhite.dwh.instance.common.query;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Метаданные списка для клиента: поля, их типы и операции фильтра, сортировка и размер страницы.
 * Отдаются тому, кто может смотреть сам список; неизвестный код и чужой список неотличимы (404),
 * чтобы по ответу нельзя было перебрать, какие списки есть.
 */
@RestController
@RequestMapping("/api/v1/query-meta")
public class QueryMetaController {

    private final QueryListRegistry registry;

    public QueryMetaController(QueryListRegistry registry) {
        this.registry = registry;
    }

    public record FieldMeta(String key, String labelKey, String type, List<String> ops, boolean sortable,
                            boolean nullable, boolean defaultVisible, List<String> enumValues,
                            String enumLabelPrefix, boolean searchable) {

        static FieldMeta of(QueryField field) {
            List<String> ops = field.ops().stream().map(QueryOp::wire).toList();
            return new FieldMeta(field.key(), field.labelKey(), field.type().wire(), ops, field.sortable(),
                    field.nullable(), field.defaultVisible(), field.enumValues(), field.enumLabelPrefix(),
                    field.searchable());
        }
    }

    public record ListMeta(String code, List<FieldMeta> fields, String defaultSort, int defaultLimit, int maxLimit,
                           int maxConditions, int maxInValues) {
    }

    /**
     * Любой вошедший пользователь (у всех ролей есть {@code iam.profile:view}); право на сам список
     * проверяется ниже по реестру. Строка, а не {@code MdPref}: {@code common} не зависит от модулей.
     */
    @GetMapping("/{code}")
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<ListMeta> get(@PathVariable String code) {
        QueryList list = registry.find(code)
                .filter(found -> SecurityContext.hasPermission(found.form(), found.action()))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "QUERY_LIST_NOT_FOUND"));
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
