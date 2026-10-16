package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Module;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Closed create-only input; preparing a package never executes code or changes a registry (ADR-0037). */
@Component
public class MdSimpleFormDeclarations {
    public static final int MAX_BYTES = 32 * 1024;
    static final Set<String> ROOT = Set.of("formatVersion", "kind", "module", "entity", "fields");
    static final Set<String> FIELD = Set.of("id", "type", "labels", "required", "listed", "maxLength", "scale");
    static final Set<String> TYPES =
            Set.of("text", "textarea", "number", "date", "datetime", "time", "bool", "email", "phone", "url");
    private final ObjectMapper json;
    private final ObjectReader reader;
    private final MdSimpleFormIdentifiers identifiers;

    public MdSimpleFormDeclarations(ObjectMapper json) {
        this.json = json;
        identifiers = new MdSimpleFormIdentifiers(json);
        reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public DeliveryPackage prepare(byte[] body) {
        JsonNode root = parse(body);
        known(root, ROOT, "declaration");
        integer(root, "formatVersion", "formatVersion", 1, 1);
        if (!"simple-form".equals(text(root, "kind", "kind"))) throw invalid("kind", "unsupported");
        JsonNode module = required(root, "module", "module");
        known(module, Set.of("code", "title"), "module");
        JsonNode entity = required(root, "entity", "entity");
        known(entity, Set.of("name", "title"), "entity");
        String moduleCode = identifier(module, "code", "module.code", "[a-z][a-z0-9]{1,11}");
        if (identifiers.reservedModule(moduleCode)) throw invalid("module.code", "identifier");
        var declaration = new Declaration(
                1,
                "simple-form",
                new Module(moduleCode, labels(module, "title", "module.title")),
                new Entity(
                        identifier(entity, "name", "entity.name", "[a-z][a-z0-9_]{0,19}"),
                        labels(entity, "title", "entity.title")),
                fields(required(root, "fields", "fields")));
        return new DeliveryPackage(1, "simple-form-package", declaration, digest(declaration));
    }

    private List<Field> fields(JsonNode fields) {
        if (!fields.isArray()) throw invalid("fields", "structure");
        if (fields.size() > 20) throw invalid("fields", "limit");
        List<Field> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            String at = "fields[" + index + "]";
            JsonNode field = fields.get(index);
            known(field, FIELD, at);
            String id = identifier(field, "id", at + ".id", "[a-z][a-z0-9]{0,19}");
            if (identifiers.reservedField(id)) throw invalid(at + ".id", "identifier");
            if (!ids.add(id)) throw invalid(at + ".id", "duplicate");
            String type = text(field, "type", at + ".type");
            if (!TYPES.contains(type)) throw invalid(at + ".type", "unsupported");
            boolean textual = Set.of("text", "textarea").contains(type);
            boolean number = "number".equals(type);
            if (!textual && field.has("maxLength")) throw invalid(at + ".maxLength", "unsupported");
            if (!number && field.has("scale")) throw invalid(at + ".scale", "unsupported");
            result.add(new Field(
                    id,
                    type,
                    labels(field, "labels", at + ".labels"),
                    bool(field, "required", at + ".required"),
                    bool(field, "listed", at + ".listed"),
                    textual ? integer(field, "maxLength", at + ".maxLength", 1, 4000) : null,
                    number ? integer(field, "scale", at + ".scale", 0, 6) : null));
        }
        return List.copyOf(result);
    }

    JsonNode parse(byte[] body) {
        requireSize(body.length);
        try {
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            try (var parser = reader.createParser(source)) {
                int depth = 0;
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token.isStructStart() && ++depth > 8) throw invalid("declaration", "limit");
                    if (token.isStructEnd()) depth--;
                }
            }
            return reader.readTree(source);
        } catch (CharacterCodingException | JacksonException malformed) {
            throw invalid("declaration", "json");
        }
    }

    private String digest(Declaration declaration) {
        byte[] canonical = json.writeValueAsBytes(canonical(json.valueToTree(declaration)));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode();
            for (String key : new TreeSet<>(value.propertyNames())) result.set(key, canonical(value.path(key)));
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    public static void requireSize(long bytes) {
        if (bytes > MAX_BYTES) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "error.payload_too_large");
    }

    private static Labels labels(JsonNode owner, String key, String at) {
        JsonNode value = required(owner, key, at);
        known(value, Set.of("ru", "uz", "en"), at);
        return new Labels(label(value, "ru", at), label(value, "uz", at), label(value, "en", at));
    }

    private static String label(JsonNode value, String language, String at) {
        String text = text(value, language, at + "." + language);
        if (text.isEmpty()
                || text.codePointCount(0, text.length()) > 80
                || space(text.codePointAt(0))
                || space(text.codePointBefore(text.length()))
                || text.codePoints()
                        .anyMatch(point -> Character.isISOControl(point)
                                || Character.getType(point) == Character.FORMAT
                                || point == 0x2028
                                || point == 0x2029
                                || (point >= 0xd800 && point <= 0xdfff))) {
            throw invalid(at + "." + language, "label");
        }
        return text;
    }

    private static boolean space(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    static void known(JsonNode value, Set<String> keys, String at) {
        if (value == null || !value.isObject()) throw invalid(at, "structure");
        if (!keys.containsAll(value.propertyNames())) throw invalid(at, "unknown_property");
    }

    static JsonNode required(JsonNode owner, String key, String at) {
        JsonNode value = owner.get(key);
        if (value == null) throw invalid(at, "missing");
        return value;
    }

    static String text(JsonNode owner, String key, String at) {
        JsonNode value = required(owner, key, at);
        if (!value.isString()) throw invalid(at, "structure");
        return value.asString();
    }

    private static String identifier(JsonNode owner, String key, String at, String pattern) {
        String value = text(owner, key, at);
        if (!value.matches(pattern)) throw invalid(at, "identifier");
        return value;
    }

    static boolean bool(JsonNode owner, String key, String at) {
        JsonNode value = required(owner, key, at);
        if (!value.isBoolean()) throw invalid(at, "structure");
        return value.booleanValue();
    }

    private static int integer(JsonNode owner, String key, String at, int min, int max) {
        JsonNode value = required(owner, key, at);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid(at, "structure");
        int number = value.intValue();
        if (number < min || number > max) throw invalid(at, "limit");
        return number;
    }

    static ApiException invalid(String at, String rule) {
        String key = switch (rule) {
            case "structure" -> "error.md.simple_form_structure";
            case "unknown_property" -> "error.md.simple_form_unknown_property";
            case "unsupported" -> "error.md.simple_form_unsupported";
            case "duplicate" -> "error.md.simple_form_duplicate";
            case "missing" -> "error.md.simple_form_missing";
            case "limit" -> "error.md.simple_form_limit";
            case "label" -> "error.md.simple_form_label";
            case "identifier" -> "error.md.simple_form_identifier";
            default -> "error.md.simple_form_json";
        };
        return ApiException.validation(
                "error.md.simple_form_invalid", List.of(FieldErrorItem.keyed(at, "simple_form_" + rule, key)));
    }
}
