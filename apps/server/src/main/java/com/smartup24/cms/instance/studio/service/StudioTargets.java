package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.LineField;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import com.smartup24.cms.instance.studio.repository.StudioListRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The targets of the reference fields — and of the reference columns of a document's lines (ADR-0043) — of a new form
 * or version exist when it is activated (ADR-0041): a list is one an
 * administrator keeps, a form is a live form served now (or the form itself). A target that disappears later — a form
 * quarantined or broken — leaves the field's old values without names and refuses new ones, never failing a request.
 */
@Component
public class StudioTargets {
    /** The message of a target that does not exist, with the field's address and the target's code. */
    public static final String NOT_FOUND = "error.studio.target_not_found";

    private final StudioListRepository lists;
    private final EntityRegistry entities;

    public StudioTargets(StudioListRepository lists, EntityRegistry entities) {
        this.lists = lists;
        this.entities = entities;
    }

    /** 422 naming every served field whose target does not exist; {@code code} is the form's own code. */
    public void require(String code, Declaration declaration) {
        List<FieldErrorItem> problems = new ArrayList<>();
        String missing = "";
        List<Field> fields = declaration.fields();
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            Target target = field.target();
            if (target == null || Boolean.TRUE.equals(field.hidden()) || exists(code, target)) continue;
            if (problems.isEmpty()) missing = target.code();
            problems.add(FieldErrorItem.keyed(
                    "fields[" + index + "].target.code", "target_not_found", NOT_FOUND, Map.of("code", target.code())));
        }
        List<Lines> lines = declaration.lines() == null ? List.of() : declaration.lines();
        for (int table = 0; table < lines.size(); table++) {
            List<LineField> columns = lines.get(table).fields();
            for (int index = 0; index < columns.size(); index++) {
                Target target = columns.get(index).target();
                if (target == null || exists(code, target)) continue;
                if (problems.isEmpty()) missing = target.code();
                problems.add(FieldErrorItem.keyed(
                        "lines[" + table + "].fields[" + index + "].target.code",
                        "target_not_found",
                        NOT_FOUND,
                        Map.of("code", target.code())));
            }
        }
        if (!problems.isEmpty()) throw ApiException.validation(NOT_FOUND, Map.of("code", missing), problems);
    }

    private boolean exists(String code, Target target) {
        if ("list".equals(target.kind())) return lists.find(target.code()).isPresent();
        return target.code().equals(code)
                || (target.code().startsWith("studio.")
                        && entities.find(target.code()).isPresent());
    }
}
