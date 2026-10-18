package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardRequest;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardWidgetRequest;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportCatalogFilter;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Bounded, closed preference payloads; client-supplied references never become SQL (ADR-0036). */
@Component
public class MdReportPreferencesInput {
    public static final int MAX_BODY_BYTES = 32 * 1024;
    public static final int MAX_WIDGETS = 12;
    public static final int MAX_ROLES = 20;
    private static final int MAX_DEPTH = 8;
    private static final long MAX_ID = 9_007_199_254_740_991L;
    private static final Pattern FIELD = Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");
    private final ObjectReader reader;

    public MdReportPreferencesInput(ObjectMapper json) {
        reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public DashboardRequest dashboard(HttpServletRequest request) throws IOException {
        JsonNode root = parse(read(request));
        known(root, Set.of("name", "widgets"), "request");
        return new DashboardRequest(name(text(root.get("name"), "name")), widgets(root.get("widgets")));
    }

    /**
     * The narrowing of the reports catalog: a list code, a part of the name of 1 to 80 characters, {@code mine} or
     * {@code shared}, {@code report} or {@code widget}; 422 at the parameter otherwise.
     */
    public ReportCatalogFilter catalog(
            @Nullable String listCode, @Nullable String q, @Nullable String owner, @Nullable String kind) {
        String name = q == null || q.isBlank() ? null : q.strip();
        if (name != null && name.codePointCount(0, name.length()) > 80) throw invalid("q", "limit");
        if (owner != null && !Set.of("mine", "shared").contains(owner)) throw invalid("owner", "structure");
        if (kind != null && !Set.of("report", "widget").contains(kind)) throw invalid("kind", "structure");
        return new ReportCatalogFilter(listCode, name, owner, kind);
    }

    public SharingRequest sharing(HttpServletRequest request) throws IOException {
        JsonNode root = parse(read(request));
        known(root, Set.of("roleIds"), "request");
        JsonNode roles = root.get("roleIds");
        array(roles, "roleIds", MAX_ROLES);
        Set<Long> seen = new HashSet<>();
        List<Long> result = new ArrayList<>();
        for (JsonNode role : roles) {
            long id = identifier(role, "roleIds");
            if (!seen.add(id)) throw invalid("roleIds", "structure");
            result.add(id);
        }
        return new SharingRequest(List.copyOf(result));
    }

    public List<DashboardWidgetRequest> widgets(JsonNode value) {
        array(value, "widgets", MAX_WIDGETS);
        List<DashboardWidgetRequest> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < value.size(); i++) {
            JsonNode widget = value.get(i);
            String at = "widgets[" + i + "]";
            known(widget, Set.of("viewId", "width", "periodField"), at);
            long id = identifier(widget.get("viewId"), at + ".viewId");
            if (!seen.add(id)) throw invalid(at + ".viewId", "structure");
            JsonNode width = widget.get("width");
            if (width == null
                    || !width.isIntegralNumber()
                    || !width.canConvertToInt()
                    || width.intValue() < 1
                    || width.intValue() > 2) throw invalid(at + ".width", "structure");
            String field = widget.has("periodField") ? text(widget.get("periodField"), at + ".periodField") : null;
            if (field != null && !FIELD.matcher(field).matches()) throw invalid(at + ".periodField", "structure");
            result.add(new DashboardWidgetRequest(id, field, width.intValue()));
        }
        return List.copyOf(result);
    }

    private static String name(String value) {
        String result = value.strip();
        if (result.isBlank()
                || result.codePointCount(0, result.length()) > 80
                || result.codePoints()
                        .anyMatch(
                                point -> Character.isISOControl(point) || Character.getType(point) == Character.FORMAT))
            throw invalid("name", "name");
        return result;
    }

    private static byte[] read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw tooLarge();
        return body;
    }

    private JsonNode parse(byte[] body) {
        try {
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            try (var parser = reader.createParser(source)) {
                int depth = 0;
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token.isStructStart() && ++depth > MAX_DEPTH) throw invalid("request", "limit");
                    if (token.isStructEnd()) depth--;
                }
            }
            return reader.readTree(source);
        } catch (CharacterCodingException | JacksonException malformed) {
            throw invalid("request", "json");
        }
    }

    private static void known(JsonNode value, Set<String> keys, String at) {
        if (value == null || !value.isObject()) throw invalid(at, "structure");
        if (!keys.containsAll(value.propertyNames())) throw invalid(at, "unknown_property");
    }

    private static void array(JsonNode value, String at, int max) {
        if (value == null || !value.isArray()) throw invalid(at, "structure");
        if (value.size() > max) throw invalid(at, "limit");
    }

    private static String text(JsonNode value, String at) {
        if (value == null || !value.isString()) throw invalid(at, "structure");
        return value.asString();
    }

    private static long identifier(JsonNode value, String at) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 1
                || value.longValue() > MAX_ID) throw invalid(at, "structure");
        return value.longValue();
    }

    public static ApiException invalid(String at, String rule) {
        String key = switch (rule) {
            case "limit" -> "error.md.report_preferences_limit";
            case "name" -> "error.md.report_preferences_name";
            case "unknown_property" -> "error.md.report_preferences_unknown_property";
            case "json" -> "error.md.report_preferences_json";
            default -> "error.md.report_preferences_structure";
        };
        return ApiException.validation(
                "error.md.report_preferences_invalid",
                List.of(FieldErrorItem.keyed(at, "REPORT_PREFERENCES_INVALID", key)));
    }

    private static ApiException tooLarge() {
        return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "error.payload_too_large");
    }
}
