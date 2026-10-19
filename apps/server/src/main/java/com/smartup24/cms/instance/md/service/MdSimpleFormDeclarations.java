package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Menu;
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

/**
 * Closed input; preparing a package never executes code or changes a registry (ADR-0037). Format 1 is the create-only
 * form every delivery takes; format 2 adds hidden fields, fixed defaults and a menu item for live forms (ADR-0041), which
 * the developer CLI refuses; format 3 adds select, reference and user fields and the data scope ({@link
 * MdSimpleFormKinds}); format 4 adds the title field and the layout ({@link MdSimpleFormLayouts}); format 5 adds the
 * formulas of the fields and the validation rules ({@link MdSimpleFormLogic}, ADR-0042); format 6 adds the tables of
 * lines, the number and the statuses of a document ({@link MdSimpleFormDocuments}, {@link MdSimpleFormWorkflows},
 * ADR-0043); format 7 adds the file field ({@link MdSimpleFormFiles}).
 */
@Component
public class MdSimpleFormDeclarations {
    public static final int MAX_BYTES = 32 * 1024;
    /** The newest simple-form format this installation reads (ADR-0041, ADR-0043). */
    public static final int MAX_FORMAT = 7;

    static final Set<String> ROOT = Set.of("formatVersion", "kind", "module", "entity", "fields");
    /** Format 4 adds the layout of the form (ADR-0041). */
    static final Set<String> ROOT_V4 = Set.of("formatVersion", "kind", "module", "entity", "fields", "layout");
    /** Format 5 adds the validation rules of the form (ADR-0042). */
    static final Set<String> ROOT_V5 = Set.of("formatVersion", "kind", "module", "entity", "fields", "layout", "rules");
    /** Format 6 adds the tables of lines and the statuses of a document (ADR-0043). */
    static final Set<String> ROOT_V6 =
            Set.of("formatVersion", "kind", "module", "entity", "fields", "layout", "rules", "lines", "workflow");

    static final Set<String> FIELD = Set.of("id", "type", "labels", "required", "listed", "maxLength", "scale");
    /** Format 2 adds a hidden flag and a fixed default to a field and a menu item to the entity (ADR-0041). */
    static final Set<String> FIELD_V2 =
            Set.of("id", "type", "labels", "required", "listed", "maxLength", "scale", "hidden", "defaultValue");
    /** Format 3 adds the choices of a select and the target of a reference (ADR-0041). */
    static final Set<String> FIELD_V3 = Set.of(
            "id",
            "type",
            "labels",
            "required",
            "listed",
            "maxLength",
            "scale",
            "hidden",
            "defaultValue",
            "options",
            "target");
    /** Format 5 adds the formulas of a field (ADR-0042). */
    static final Set<String> FIELD_V5 = Set.of(
            "id",
            "type",
            "labels",
            "required",
            "listed",
            "maxLength",
            "scale",
            "hidden",
            "defaultValue",
            "options",
            "target",
            "formula",
            "defaultFormula",
            "visibleIf",
            "requiredIf",
            "readonlyIf");
    /** The menu icons and sections a live form may take. */
    public static final Set<String> MENU_ICONS = Set.of(
            "box",
            "description",
            "assignment",
            "event",
            "storefront",
            "local_shipping",
            "inventory_2",
            "people",
            "support_agent",
            "receipt_long");

    public static final Set<String> MENU_SECTIONS = Set.of("workspace", "administration");
    /** Fields of a declaration and, of them, the fields served: hidden ones keep their key (ADR-0041). */
    public static final int MAX_FIELDS = 30;

    public static final int MAX_SHOWN_FIELDS = 20;
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
        int format = integer(root, "formatVersion", "formatVersion", 1, MAX_FORMAT);
        known(root, format >= 6 ? ROOT_V6 : format == 5 ? ROOT_V5 : format == 4 ? ROOT_V4 : ROOT, "declaration");
        if (!"simple-form".equals(text(root, "kind", "kind"))) throw invalid("kind", "unsupported");
        JsonNode module = required(root, "module", "module");
        known(module, Set.of("code", "title"), "module");
        JsonNode entity = required(root, "entity", "entity");
        known(entity, entityKeys(format), "entity");
        String moduleCode = identifier(module, "code", "module.code", "[a-z][a-z0-9]{1,11}");
        if (identifiers.reservedModule(moduleCode)) throw invalid("module.code", "identifier");
        String name = identifier(entity, "name", "entity.name", "[a-z][a-z0-9_]{0,19}");
        Labels title = labels(entity, "title", "entity.title");
        Menu menu = entity.has("menu") ? menu(entity.get("menu")) : null;
        String scope = entity.has("scope") ? MdSimpleFormKinds.scope(entity) : null;
        List<Field> fields = fields(required(root, "fields", "fields"), format);
        var rules = MdSimpleFormLogic.rules(root, fields);
        var numbering = MdSimpleFormDocuments.numbering(entity);
        var lines = MdSimpleFormDocuments.lines(root, fields, identifiers::reservedField);
        var workflow = MdSimpleFormWorkflows.workflow(root, fields, lines);
        MdSimpleFormWorkflows.assignOn(numbering, workflow);
        var declaration = new Declaration(
                format,
                "simple-form",
                new Module(moduleCode, labels(module, "title", "module.title")),
                new Entity(name, title, menu, scope, MdSimpleFormLayouts.titleField(entity, fields), numbering),
                fields,
                MdSimpleFormLayouts.layout(root, fields),
                rules,
                lines,
                workflow);
        if (format == 5) MdSimpleFormLogic.check(fields, rules);
        if (format >= 6) MdSimpleFormDocumentLogic.check(fields, rules, lines, workflow, numbering);
        return new DeliveryPackage(1, "simple-form-package", declaration, digest(declaration));
    }

    private static Set<String> entityKeys(int format) {
        return switch (format) {
            case 1 -> Set.of("name", "title");
            case 2 -> Set.of("name", "title", "menu");
            case 3 -> Set.of("name", "title", "menu", "scope");
            case 4, 5 -> Set.of("name", "title", "menu", "scope", "titleField");
            default -> Set.of("name", "title", "menu", "scope", "titleField", "numbering");
        };
    }

    /** The keys a field of the format may have. */
    private static Set<String> keys(int format) {
        return switch (format) {
            case 1 -> FIELD;
            case 2 -> FIELD_V2;
            case 3, 4 -> FIELD_V3;
            case 5, 6 -> FIELD_V5;
            default -> MdSimpleFormFiles.FIELD_V7;
        };
    }

    private static Menu menu(JsonNode menu) {
        known(menu, Set.of("icon", "section", "order"), "entity.menu");
        String icon = text(menu, "icon", "entity.menu.icon");
        if (!MENU_ICONS.contains(icon)) throw invalid("entity.menu.icon", "unsupported");
        String section = text(menu, "section", "entity.menu.section");
        if (!MENU_SECTIONS.contains(section)) throw invalid("entity.menu.section", "unsupported");
        return new Menu(icon, section, integer(menu, "order", "entity.menu.order", 1, 999));
    }

    private List<Field> fields(JsonNode fields, int format) {
        if (!fields.isArray()) throw invalid("fields", "structure");
        if (fields.size() > (format == 1 ? MAX_SHOWN_FIELDS : MAX_FIELDS)) throw invalid("fields", "limit");
        List<Field> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            String at = "fields[" + index + "]";
            JsonNode field = fields.get(index);
            known(field, keys(format), at);
            String id = identifier(field, "id", at + ".id", "[a-z][a-z0-9]{0,19}");
            if (identifiers.reservedField(id)) throw invalid(at + ".id", "identifier");
            if (!ids.add(id)) throw invalid(at + ".id", "duplicate");
            String type = text(field, "type", at + ".type");
            if (!TYPES.contains(type)
                    && !(format >= 3 && MdSimpleFormKinds.TYPES.contains(type))
                    && !(format >= 7 && MdSimpleFormFiles.TYPE.equals(type)))
                throw invalid(at + ".type", "unsupported");
            boolean textual = Set.of("text", "textarea").contains(type);
            boolean number = "number".equals(type);
            if (!textual && field.has("maxLength")) throw invalid(at + ".maxLength", "unsupported");
            if (!number && field.has("scale")) throw invalid(at + ".scale", "unsupported");
            boolean hidden = field.has("hidden") && bool(field, "hidden", at + ".hidden");
            MdSimpleFormKinds.Parts parts = MdSimpleFormKinds.parts(field, type, at);
            var files = MdSimpleFormFiles.files(field, type, at);
            String fallback = field.has("defaultValue") ? defaultValue(field, type, at + ".defaultValue") : null;
            if (fallback != null && "select".equals(type) && !MdSimpleFormKinds.offers(parts.options(), fallback))
                throw invalid(at + ".defaultValue", "default");
            result.add(new Field(
                    id,
                    type,
                    labels(field, "labels", at + ".labels"),
                    bool(field, "required", at + ".required"),
                    bool(field, "listed", at + ".listed"),
                    textual ? integer(field, "maxLength", at + ".maxLength", 1, 4000) : null,
                    number ? integer(field, "scale", at + ".scale", 0, 6) : null,
                    hidden ? Boolean.TRUE : null,
                    fallback,
                    parts.options(),
                    parts.target(),
                    MdSimpleFormLogic.formula(field, "formula", at + ".formula"),
                    MdSimpleFormLogic.formula(field, "defaultFormula", at + ".defaultFormula"),
                    MdSimpleFormLogic.formula(field, "visibleIf", at + ".visibleIf"),
                    MdSimpleFormLogic.formula(field, "requiredIf", at + ".requiredIf"),
                    MdSimpleFormLogic.formula(field, "readonlyIf", at + ".readonlyIf"),
                    files));
        }
        if (result.stream().filter(field -> field.hidden() == null).count() > MAX_SHOWN_FIELDS)
            throw invalid("fields", "limit");
        return List.copyOf(result);
    }

    /**
     * A fixed default as text: at most 4000 characters, without control or format characters (a text area may break
     * lines), and a yes/no default spelled {@code true} or {@code false}. Whether the field accepts the value is checked
     * against the compiled field by the live activation, with the rules every save applies (ADR-0041).
     */
    private static String defaultValue(JsonNode field, String type, String at) {
        String value = text(field, "defaultValue", at);
        boolean area = "textarea".equals(type);
        if (value.isEmpty()
                || value.codePointCount(0, value.length()) > 4000
                || value.codePoints()
                        .anyMatch(point -> (Character.isISOControl(point) && !(area && point == '\n'))
                                || Character.getType(point) == Character.FORMAT
                                || point == 0x2028
                                || point == 0x2029
                                || (point >= 0xd800 && point <= 0xdfff))) {
            throw invalid(at, "default");
        }
        if ("bool".equals(type) && !Set.of("true", "false").contains(value)) throw invalid(at, "default");
        return value;
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

    static Labels labels(JsonNode owner, String key, String at) {
        JsonNode value = required(owner, key, at);
        known(value, Set.of("ru", "uz", "en"), at);
        return new Labels(label(value, "ru", at), label(value, "uz", at), label(value, "en", at));
    }

    private static String label(JsonNode value, String language, String at) {
        String text = text(value, language, at + "." + language);
        if (!validLabel(text)) throw invalid(at + "." + language, "label");
        return text;
    }

    /** A name of 1 to 80 characters, without spaces at its edges, control or format characters. */
    public static boolean validLabel(String text) {
        return !text.isEmpty()
                && text.codePointCount(0, text.length()) <= 80
                && !space(text.codePointAt(0))
                && !space(text.codePointBefore(text.length()))
                && text.codePoints()
                        .noneMatch(point -> Character.isISOControl(point)
                                || Character.getType(point) == Character.FORMAT
                                || point == 0x2028
                                || point == 0x2029
                                || (point >= 0xd800 && point <= 0xdfff));
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

    static int integer(JsonNode owner, String key, String at, int min, int max) {
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
            case "default" -> "error.md.simple_form_default";
            case "title" -> "error.md.simple_form_title";
            case "layout" -> "error.md.simple_form_layout";
            case "formula" -> "error.md.simple_form_formula";
            case "logic" -> "error.md.simple_form_logic";
            case "numbering" -> "error.md.simple_form_numbering";
            case "workflow" -> "error.md.simple_form_workflow";
            default -> "error.md.simple_form_json";
        };
        return ApiException.validation(
                "error.md.simple_form_invalid", List.of(FieldErrorItem.keyed(at, "simple_form_" + rule, key)));
    }
}
