package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Independently verifies the closed package, never treating its digest as authority (ADR-0038). */
@Component
public class StudioPackages {
    public static final int MAX_BYTES = 64 * 1024;
    private final ObjectMapper json;
    private final ObjectReader reader;
    private final MdSimpleFormDeclarations declarations;

    public StudioPackages(ObjectMapper json, MdSimpleFormDeclarations declarations) {
        this.json = json;
        this.declarations = declarations;
        reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public DeliveryPackage verify(byte[] body) {
        JsonNode root = parse(body);
        if (root == null
                || !root.isObject()
                || !root.propertyNames().equals(Set.of("formatVersion", "kind", "declaration", "checksum"))
                || !root.path("formatVersion").isIntegralNumber()
                || !root.path("formatVersion").canConvertToInt()
                || root.path("formatVersion").intValue() != 1
                || !root.path("kind").isString()
                || !"simple-form-package".equals(root.path("kind").asString())
                || !root.path("checksum").isString()
                || !root.path("checksum").asString().matches("[0-9a-f]{64}")) throw invalid();
        DeliveryPackage checked = declarations.prepare(json.writeValueAsBytes(root.get("declaration")));
        if (!checked.checksum().equals(root.path("checksum").asString())) throw invalid();
        if (!"studio".equals(checked.declaration().module().code())
                || Set.of("forms", "records", "activation")
                        .contains(checked.declaration().entity().name())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.namespace");
        }
        return checked;
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
                    if (token.isStructStart() && ++depth > 10) throw invalid();
                    if (token.isStructEnd()) depth--;
                }
            }
            return reader.readTree(source);
        } catch (CharacterCodingException | JacksonException malformed) {
            throw invalid();
        }
    }

    public static void requireSize(long bytes) {
        if (bytes > MAX_BYTES) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "error.payload_too_large");
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.package_invalid");
    }
}
