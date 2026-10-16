package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutDeclaration;
import com.smartup24.cms.instance.common.entity.FormLayoutDeclarations;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioDependencies;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioPublicationPackage;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVersionPackage;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/** Closed package format and canonical checksums, never executable configuration (ADR-0035). */
@Component
public class MdFormStudioPackages {
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_DEPTH = 10;
    private final ObjectMapper json;
    private final ObjectReader reader;
    private final JsonColumns stored;
    private final FormLayoutDeclarations declarations;

    public MdFormStudioPackages(ObjectMapper json, FormLayoutDeclarations declarations) {
        this.json = json;
        this.declarations = declarations;
        this.stored = new JsonColumns(json, "md_form_layout_versions");
        reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public FormStudioVersionPackage prepare(
            long version, long revision, FormLayoutDeclaration declaration, FormMeta meta) {
        requireInteger(version);
        requireInteger(revision);
        var artifact = new FormStudioPublicationPackage(
                1,
                "form-layout-package",
                declaration.entityCode(),
                version,
                revision,
                new FormStudioDependencies(1, digest(meta)),
                declaration);
        var envelope = new FormStudioVersionPackage(version, revision, digest(artifact), artifact);
        requireSize(json.writeValueAsBytes(envelope).length);
        return envelope;
    }

    public String write(FormStudioPublicationPackage artifact) {
        return stored.write(artifact);
    }

    public FormStudioPublicationPackage read(String artifact) {
        return stored.read(artifact, FormStudioPublicationPackage.class);
    }

    public void requireCompatible(FormStudioPublicationPackage artifact, FormMeta meta) {
        declarations.validate(json.writeValueAsBytes(artifact.declaration()), meta);
        if (artifact.dependencies().contractVersion() != 1
                || !digest(meta).equals(artifact.dependencies().metadataChecksum())) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.md.form_studio_dependencies_changed");
        }
    }

    public void requireVisible(FormStudioPublicationPackage artifact, FormMeta meta) {
        declarations.validate(json.writeValueAsBytes(artifact.declaration()), meta);
    }

    public FormStudioVersionPackage validate(byte[] body, FormMeta meta) {
        JsonNode root = parse(body);
        known(root, Set.of("version", "sourceRevision", "checksum", "package"));
        long version = number(root, "version");
        long revision = number(root, "sourceRevision");
        String checksum = checksum(root, "checksum");
        JsonNode source = root.path("package");
        known(
                source,
                Set.of(
                        "formatVersion",
                        "kind",
                        "entityCode",
                        "version",
                        "sourceRevision",
                        "dependencies",
                        "declaration"));
        if (number(source, "formatVersion") != 1
                || !"form-layout-package".equals(text(source, "kind"))
                || !FormLayoutDeclarations.ENTITY_CODE.equals(text(source, "entityCode"))) throw invalid();
        if (version != number(source, "version") || revision != number(source, "sourceRevision")) throw invalid();
        JsonNode dependencies = source.path("dependencies");
        known(dependencies, Set.of("contractVersion", "metadataChecksum"));
        if (number(dependencies, "contractVersion") != 1) throw invalid();
        var dependency = new FormStudioDependencies(1, checksum(dependencies, "metadataChecksum"));
        var declaration = declarations.validate(json.writeValueAsBytes(source.path("declaration")), meta);
        var artifact = new FormStudioPublicationPackage(
                1,
                "form-layout-package",
                FormLayoutDeclarations.ENTITY_CODE,
                version,
                revision,
                dependency,
                declaration);
        if (!checksum.equals(digest(artifact))) throw invalid();
        return new FormStudioVersionPackage(version, revision, checksum, artifact);
    }

    /** Object keys are sorted recursively; array order and exact strings remain part of the digest. */
    public String digest(Object value) {
        byte[] canonical = json.writeValueAsBytes(canonical(json.valueToTree(value)));
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

    private JsonNode parse(byte[] body) {
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
                    if (token.isStructStart() && ++depth > MAX_DEPTH) throw invalid();
                    if (token.isStructEnd()) depth--;
                }
            }
            return reader.readTree(source);
        } catch (CharacterCodingException | JacksonException malformed) {
            throw invalid();
        }
    }

    public static void requireSize(long size) {
        if (size > MAX_BYTES) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "error.payload_too_large");
    }

    private static void known(JsonNode value, Set<String> keys) {
        if (value == null || !value.isObject() || !keys.equals(value.propertyNames())) throw invalid();
    }

    private static long number(JsonNode owner, String key) {
        JsonNode value = owner.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        long number = value.longValue();
        requireInteger(number);
        return number;
    }

    private static void requireInteger(long number) {
        if (number < 1 || number > MdFormStudioDtos.MAX_INTEGER) throw invalid();
    }

    private static String text(JsonNode owner, String key) {
        JsonNode value = owner.path(key);
        if (!value.isString()) throw invalid();
        return value.asString();
    }

    private static String checksum(JsonNode owner, String key) {
        String value = text(owner, key);
        if (!value.matches("[0-9a-f]{64}")) throw invalid();
        return value;
    }

    public static ApiException invalid() {
        return ApiException.validation(
                "error.md.form_studio_package_invalid",
                List.of(FieldErrorItem.keyed(
                        "package", "FORM_PACKAGE_INVALID", "error.md.form_studio_package_invalid")));
    }
}
