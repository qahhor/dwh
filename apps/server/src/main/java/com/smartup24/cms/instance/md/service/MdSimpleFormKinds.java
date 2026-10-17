package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.invalid;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.known;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.labels;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.text;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The parts format 3 of a simple form adds (ADR-0041): the choices of a select field, the target of a reference field
 * and the data scope of the form. Whether a target exists is checked by the live activation, which knows the lists and
 * the forms; here only the shape.
 */
final class MdSimpleFormKinds {
    /** The field types of format 3 that format 2 does not have. */
    static final Set<String> TYPES = Set.of("select", "ref", "user");

    static final Set<String> SCOPES = Set.of("all", "org_unit", "owner");

    /** Choices of one select field. */
    static final int MAX_OPTIONS = 50;

    private static final String OPTION_CODE = "[a-z0-9][a-z0-9_-]{0,39}";
    private static final String LIST_CODE = "[a-z][a-z0-9_]{0,39}";
    private static final String FORM_CODE = "studio\\.[a-z][a-z0-9_]{0,19}";

    private MdSimpleFormKinds() {}

    static String scope(JsonNode entity) {
        String scope = text(entity, "scope", "entity.scope");
        if (!SCOPES.contains(scope)) throw invalid("entity.scope", "unsupported");
        return scope;
    }

    /**
     * The choices and the target of a field as its type takes them: a select has 1 to 50 choices with unique codes, a
     * reference names a list or a live form; no other type has either.
     */
    static Parts parts(JsonNode field, String type, String at) {
        boolean select = "select".equals(type);
        boolean ref = "ref".equals(type);
        if (!select && field.has("options")) throw invalid(at + ".options", "unsupported");
        if (!ref && field.has("target")) throw invalid(at + ".target", "unsupported");
        if (TYPES.contains(type) && !select && field.has("defaultValue"))
            throw invalid(at + ".defaultValue", "unsupported");
        List<Option> options =
                select ? options(MdSimpleFormDeclarations.required(field, "options", at + ".options"), at) : null;
        Target target = ref ? target(MdSimpleFormDeclarations.required(field, "target", at + ".target"), at) : null;
        return new Parts(options, target);
    }

    /** The choices and the target of one field; null where its type has none. */
    record Parts(@Nullable List<Option> options, @Nullable Target target) {}

    /** Whether a select's default is one of its choices. */
    static boolean offers(@Nullable List<Option> options, String value) {
        return options != null
                && options.stream().anyMatch(option -> option.code().equals(value));
    }

    private static List<Option> options(JsonNode options, String at) {
        String path = at + ".options";
        if (!options.isArray()) throw invalid(path, "structure");
        if (options.isEmpty() || options.size() > MAX_OPTIONS) throw invalid(path, "limit");
        List<Option> result = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int index = 0; index < options.size(); index++) {
            String item = path + "[" + index + "]";
            JsonNode option = options.get(index);
            known(option, Set.of("code", "labels"), item);
            String code = text(option, "code", item + ".code");
            if (!code.matches(OPTION_CODE)) throw invalid(item + ".code", "identifier");
            if (!codes.add(code)) throw invalid(item + ".code", "duplicate");
            result.add(new Option(code, labels(option, "labels", item + ".labels")));
        }
        return List.copyOf(result);
    }

    private static Target target(JsonNode target, String at) {
        String path = at + ".target";
        known(target, Set.of("kind", "code"), path);
        String kind = text(target, "kind", path + ".kind");
        String code = text(target, "code", path + ".code");
        switch (kind) {
            case "list" -> {
                if (!code.matches(LIST_CODE)) throw invalid(path + ".code", "identifier");
            }
            case "form" -> {
                if (!code.matches(FORM_CODE)) throw invalid(path + ".code", "identifier");
            }
            default -> throw invalid(path + ".kind", "unsupported");
        }
        return new Target(kind, code);
    }
}
