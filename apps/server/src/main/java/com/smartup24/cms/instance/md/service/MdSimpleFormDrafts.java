package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.bool;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.invalid;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.known;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.required;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.text;

import com.smartup24.cms.instance.md.api.MdSimpleFormDraftDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDraftDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDraftDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDraftDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDraftDtos.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Recovers unfinished local input without making it publishable or persisting it (ADR-0037). */
@Component
public class MdSimpleFormDrafts {
    private final MdSimpleFormDeclarations declarations;

    public MdSimpleFormDrafts(MdSimpleFormDeclarations declarations) {
        this.declarations = declarations;
    }

    public Declaration check(byte[] body) {
        JsonNode root = declarations.parse(body);
        known(root, MdSimpleFormDeclarations.ROOT, "declaration");
        JsonNode version = required(root, "formatVersion", "formatVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1)
            throw invalid("formatVersion", "unsupported");
        if (!"simple-form".equals(text(root, "kind", "kind"))) throw invalid("kind", "unsupported");
        JsonNode module = required(root, "module", "module");
        known(module, Set.of("code", "title"), "module");
        JsonNode entity = required(root, "entity", "entity");
        known(entity, Set.of("name", "title"), "entity");
        return new Declaration(
                1,
                "simple-form",
                new Module(input(module, "code", "module.code"), labels(module, "title", "module.title")),
                new Entity(input(entity, "name", "entity.name"), labels(entity, "title", "entity.title")),
                fields(required(root, "fields", "fields")));
    }

    private static List<Field> fields(JsonNode fields) {
        if (!fields.isArray()) throw invalid("fields", "structure");
        if (fields.size() > 20) throw invalid("fields", "limit");
        List<Field> result = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            String at = "fields[" + index + "]";
            JsonNode field = fields.get(index);
            known(field, MdSimpleFormDeclarations.FIELD, at);
            String type = text(field, "type", at + ".type");
            if (!MdSimpleFormDeclarations.TYPES.contains(type)) throw invalid(at + ".type", "unsupported");
            if (!Set.of("text", "textarea").contains(type) && field.has("maxLength"))
                throw invalid(at + ".maxLength", "unsupported");
            if (!"number".equals(type) && field.has("scale")) throw invalid(at + ".scale", "unsupported");
            result.add(new Field(
                    input(field, "id", at + ".id"),
                    type,
                    labels(field, "labels", at + ".labels"),
                    bool(field, "required", at + ".required"),
                    bool(field, "listed", at + ".listed"),
                    number(field, "maxLength", at + ".maxLength"),
                    number(field, "scale", at + ".scale")));
        }
        return List.copyOf(result);
    }

    private static @Nullable Double number(JsonNode owner, String key, String at) {
        JsonNode value = owner.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw invalid(at, "structure");
        return value.doubleValue();
    }

    private static Labels labels(JsonNode owner, String key, String at) {
        JsonNode value = required(owner, key, at);
        known(value, Set.of("ru", "uz", "en"), at);
        return new Labels(
                input(value, "ru", at + ".ru"), input(value, "uz", at + ".uz"), input(value, "en", at + ".en"));
    }

    private static String input(JsonNode owner, String key, String at) {
        String value = text(owner, key, at);
        if (value.codePointCount(0, value.length()) > 80
                || value.codePoints()
                        .anyMatch(point -> Character.isISOControl(point)
                                || Character.getType(point) == Character.FORMAT
                                || point == 0x2028
                                || point == 0x2029
                                || (point >= 0xd800 && point <= 0xdfff))) throw invalid(at, "label");
        return value;
    }
}
