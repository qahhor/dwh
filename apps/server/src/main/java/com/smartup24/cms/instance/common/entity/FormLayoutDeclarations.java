package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutDeclaration;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutField;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutSection;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.LocalizedLabels;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.error.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** A bounded presentation-only contract; it never registers or mutates an entity. */
@Component
public class FormLayoutDeclarations {
    public static final String ENTITY_CODE = "example.products";
    public static final int MAX_BODY_BYTES = 32 * 1024;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_SECTIONS = 8;
    public static final int MAX_FIELDS = 64;
    private static final Set<String> ROOT_KEYS = Set.of("formatVersion", "kind", "entityCode", "sections");
    private static final Set<String> SECTION_KEYS = Set.of("id", "labels", "fields");
    private static final Set<String> FIELD_KEYS = Set.of("fieldId", "labels");
    private static final Set<String> LANGUAGES = Set.of("ru", "uz", "en");
    private static final Pattern SECTION_ID = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private final ObjectReader reader;

    public FormLayoutDeclarations(ObjectMapper json) {
        reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public FormLayoutDeclaration initial(FormMeta meta) {
        return new FormLayoutDeclaration(
                1,
                "form-layout",
                ENTITY_CODE,
                meta.layout().stream()
                        .map(section -> new FormLayoutSection(
                                section.key(),
                                null,
                                section.fields().stream()
                                        .map(field -> new FormLayoutField(field, null))
                                        .toList()))
                        .toList());
    }

    public FormLayoutDeclaration validate(byte[] body, FormMeta meta) {
        JsonNode root = parse(body);
        known(root, ROOT_KEYS, "declaration");
        JsonNode version = required(root, "formatVersion", "formatVersion");
        if (!version.isIntegralNumber()) throw invalid("formatVersion", "structure");
        if (!version.canConvertToInt() || version.intValue() != 1) throw invalid("formatVersion", "unsupported");
        if (!"form-layout".equals(text(root, "kind", "kind"))) throw invalid("kind", "unsupported");
        if (!ENTITY_CODE.equals(text(root, "entityCode", "entityCode"))) throw invalid("entityCode", "unsupported");
        JsonNode sections = required(root, "sections", "sections");
        array(sections, "sections", MAX_SECTIONS);
        Set<String> expected = meta.layout().stream()
                .flatMap(section -> section.fields().stream())
                .collect(Collectors.toSet());
        Set<String> existing =
                meta.layout().stream().map(section -> section.key()).collect(Collectors.toSet());
        Set<String> placed = new HashSet<>();
        Set<String> ids = new HashSet<>();
        List<FormLayoutSection> result = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < sections.size(); i++) {
            JsonNode section = sections.get(i);
            String at = "sections[" + i + "]";
            known(section, SECTION_KEYS, at);
            String id = text(section, "id", at + ".id");
            if (!SECTION_ID.matcher(id).matches()) throw invalid(at + ".id", "identifier");
            if (!ids.add(id)) throw invalid(at + ".id", "duplicate");
            LocalizedLabels labels = labels(section, at);
            if (labels == null && !existing.contains(id)) throw invalid(at + ".labels", "missing");
            JsonNode fields = required(section, "fields", at + ".fields");
            array(fields, at + ".fields", MAX_FIELDS);
            total += fields.size();
            if (total > MAX_FIELDS) throw invalid("sections", "limit");
            result.add(new FormLayoutSection(id, labels, fields(fields, at, expected, placed)));
        }
        if (!placed.equals(expected)) throw invalid("sections", "missing");
        return new FormLayoutDeclaration(1, "form-layout", ENTITY_CODE, result);
    }

    private static List<FormLayoutField> fields(JsonNode fields, String at, Set<String> expected, Set<String> placed) {
        List<FormLayoutField> result = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            JsonNode field = fields.get(i);
            String path = at + ".fields[" + i + "]";
            known(field, FIELD_KEYS, path);
            String id = text(field, "fieldId", path + ".fieldId");
            if (!expected.contains(id)) throw invalid(path + ".fieldId", "unknown_property");
            if (!placed.add(id)) throw invalid(path + ".fieldId", "duplicate");
            result.add(new FormLayoutField(id, labels(field, path)));
        }
        return result;
    }

    private JsonNode parse(byte[] body) {
        requireBodySize(body.length);
        try {
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            requireDepth(source);
            return reader.readTree(source);
        } catch (CharacterCodingException | JacksonException malformed) {
            throw invalid("declaration", "json");
        }
    }

    private void requireDepth(String source) {
        try (var parser = reader.createParser(source)) {
            int depth = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token.isStructStart() && ++depth > MAX_DEPTH) throw invalid("declaration", "limit");
                if (token.isStructEnd()) depth--;
            }
        }
    }

    public static void requireBodySize(long bytes) {
        if (bytes > MAX_BODY_BYTES) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "error.payload_too_large");
    }

    private static @Nullable LocalizedLabels labels(JsonNode owner, String at) {
        if (!owner.has("labels")) return null;
        JsonNode labels = owner.get("labels");
        known(labels, LANGUAGES, at + ".labels");
        return new LocalizedLabels(label(labels, "ru", at), label(labels, "uz", at), label(labels, "en", at));
    }

    private static String label(JsonNode labels, String language, String at) {
        String path = at + ".labels." + language;
        String value = text(labels, language, path);
        if (value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))
                || value.codePointCount(0, value.length()) > 120
                || value.codePoints()
                        .anyMatch(point ->
                                Character.isISOControl(point) || Character.getType(point) == Character.FORMAT)) {
            throw invalid(path, "label");
        }
        return value;
    }

    private static void known(JsonNode value, Set<String> keys, String at) {
        if (value == null || !value.isObject()) throw invalid(at, "structure");
        if (!keys.containsAll(value.propertyNames())) throw invalid(at, "unknown_property");
    }

    private static JsonNode required(JsonNode owner, String key, String at) {
        JsonNode value = owner.get(key);
        if (value == null) throw invalid(at, "missing");
        return value;
    }

    private static String text(JsonNode owner, String key, String at) {
        JsonNode value = required(owner, key, at);
        if (!value.isString()) throw invalid(at, "structure");
        return value.asString();
    }

    private static void array(JsonNode value, String at, int maximum) {
        if (!value.isArray()) throw invalid(at, "structure");
        if (value.isEmpty() || value.size() > maximum) throw invalid(at, "limit");
    }

    private static ApiException invalid(String at, String rule) {
        String messageKey = switch (rule) {
            case "structure" -> "error.common.form_layout_structure";
            case "unknown_property" -> "error.common.form_layout_unknown_property";
            case "unsupported" -> "error.common.form_layout_unsupported";
            case "duplicate" -> "error.common.form_layout_duplicate";
            case "missing" -> "error.common.form_layout_missing";
            case "limit" -> "error.common.form_layout_limit";
            case "label" -> "error.common.form_layout_label";
            case "identifier" -> "error.common.form_layout_identifier";
            default -> "error.common.form_layout_json";
        };
        return ApiException.validation(
                "error.common.form_layout_invalid",
                List.of(FieldErrorItem.keyed(at, "form_layout_" + rule, messageKey)));
    }
}
