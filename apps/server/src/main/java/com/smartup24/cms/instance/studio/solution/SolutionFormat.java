package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.service.StudioPackages;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * The wire form of a solution package (ADR-0047). Reading is bounded before anything is trusted: size, depth, duplicate
 * keys and trailing tokens; then the checksum over the canonical JSON (object keys sorted, array order kept) without
 * the checksum itself, the format, what the package requires of this installation, the structure and the references
 * between its parts; every form is verified again as a simple-form package (ADR-0038). A matching checksum proves that
 * the file was not changed after it was made, not who made it: every part is still checked by the paths that create
 * it.
 */
@Component
public class SolutionFormat {
    static final int MAX_DEPTH = 16;
    private static final Set<String> ROOT = Set.of(
            "formatVersion",
            "kind",
            "code",
            "version",
            "title",
            "description",
            "requires",
            "producedBy",
            "placeholders",
            "lists",
            "forms",
            "datasets",
            "reports",
            "mailings",
            "automations",
            "grants",
            "checksum");

    private final ObjectMapper json;
    private final ObjectReader tree;
    private final ObjectReader strict;
    private final StudioPackages forms;

    public SolutionFormat(ObjectMapper json, StudioPackages forms) {
        this.json = json;
        this.forms = forms;
        this.tree = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.strict = json.readerFor(SolutionPackage.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    /** The verified package of the bytes, or 413/422 with what is wrong. */
    public SolutionPackage read(byte[] body) {
        requireSize(body.length);
        JsonNode root = parse(body);
        if (!root.isObject()) throw invalid("package");
        if (!root.path("checksum").isString()
                || !root.path("checksum").asString().matches("[0-9a-f]{64}")) throw invalid("checksum");
        if (!checksum(root).equals(root.path("checksum").asString()))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.solution_checksum");
        if (!root.path("formatVersion").isIntegralNumber()
                || root.path("formatVersion").asInt() != StudioPackageDtos.FORMAT
                || !StudioPackageDtos.KIND.equals(root.path("kind").asString(null)))
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "error.studio.solution_format",
                    Map.of("supported", StudioPackageDtos.FORMAT));
        int required = root.path("requires").path("simpleFormFormat").asInt(0);
        if (required > MdSimpleFormDeclarations.MAX_FORMAT)
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "error.studio.solution_newer",
                    Map.of("format", required, "supported", MdSimpleFormDeclarations.MAX_FORMAT));
        for (String key : root.propertyNames()) if (!ROOT.contains(key)) throw invalid(key);
        SolutionPackage mapped;
        try {
            mapped = strict.readValue(root);
        } catch (JacksonException malformed) {
            throw invalid("package");
        }
        if (mapped == null) throw invalid("package");
        SolutionPackage verified = withVerifiedForms(mapped, root.path("forms"));
        SolutionChecks.require(verified);
        return verified;
    }

    /**
     * The file of the package with its checksum: what an export writes and a template ships. The checksum is taken over
     * the tree read back from the package's own text, the tree an import reads.
     */
    public byte[] seal(SolutionPackage unsealed) {
        ObjectNode node = (ObjectNode) tree.readTree(json.writeValueAsBytes(unsealed));
        node.put("checksum", checksum(node));
        return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
    }

    /** The checksum of a package's JSON: SHA-256 of its canonical form without the checksum. */
    public String checksum(JsonNode root) {
        ObjectNode copy = ((ObjectNode) root).deepCopy();
        copy.remove("checksum");
        return sha256(json.writeValueAsBytes(canonical(copy)));
    }

    /** The checksum of any part of a package: what an applied report, mailing or automation remembers. */
    public String digest(Object part) {
        return sha256(json.writeValueAsBytes(canonical(json.valueToTree(part))));
    }

    private SolutionPackage withVerifiedForms(SolutionPackage mapped, JsonNode nodes) {
        if (mapped.forms() == null || !nodes.isArray() || nodes.size() > StudioPackageDtos.MAX_FORMS)
            throw invalid("forms");
        List<DeliveryPackage> verified = new ArrayList<>();
        int index = 0;
        for (JsonNode node : nodes) {
            try {
                verified.add(forms.verify(json.writeValueAsBytes(node)));
            } catch (ApiException refused) {
                throw ApiException.validation(
                        "error.studio.solution_invalid",
                        List.of(FieldErrorItem.keyed(
                                "forms[" + index + "]",
                                "solution_invalid",
                                refused.getMessageKey(),
                                refused.getParams())));
            }
            index++;
        }
        return new SolutionPackage(
                mapped.formatVersion(),
                mapped.kind(),
                mapped.code(),
                mapped.version(),
                mapped.title(),
                mapped.description(),
                mapped.requires(),
                mapped.producedBy(),
                mapped.placeholders(),
                mapped.lists(),
                List.copyOf(verified),
                mapped.datasets(),
                mapped.reports(),
                mapped.mailings(),
                mapped.automations(),
                mapped.grants(),
                mapped.checksum());
    }

    private JsonNode parse(byte[] body) {
        try {
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            try (var parser = tree.createParser(source)) {
                int depth = 0;
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token.isStructStart() && ++depth > MAX_DEPTH) throw invalid("package");
                    if (token.isStructEnd()) depth--;
                }
            }
            return tree.readTree(source);
        } catch (CharacterCodingException | JacksonException malformed) {
            throw invalid("package");
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
        }
    }

    public static void requireSize(long bytes) {
        if (bytes > StudioPackageDtos.MAX_BYTES)
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "error.payload_too_large");
    }

    static ApiException invalid(String field) {
        return ApiException.validation(
                "error.studio.solution_invalid",
                List.of(FieldErrorItem.keyed(field, "solution_invalid", "error.studio.solution_part")));
    }
}
