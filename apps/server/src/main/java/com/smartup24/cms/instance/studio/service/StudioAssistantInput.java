package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** A closed, small request prevents metadata or row data from being added implicitly (ADR-0039). */
@Component
public class StudioAssistantInput {
    public static final int MAX_BYTES = 16 * 1024;
    private final ObjectReader reader;

    public StudioAssistantInput(ObjectMapper json) {
        reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String parse(byte[] body) {
        requireSize(body.length);
        try {
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            var root = reader.readTree(source);
            if (root == null
                    || !root.isObject()
                    || root.size() != 1
                    || !root.has("prompt")
                    || !root.path("prompt").isString()) throw invalid();
            String prompt = root.path("prompt").asString().strip();
            if (prompt.isEmpty()
                    || prompt.codePointCount(0, prompt.length()) > 2000
                    || prompt.codePoints()
                            .anyMatch(c -> (Character.isISOControl(c) && c != '\n' && c != '\t')
                                    || (c >= 0xd800 && c <= 0xdfff))) throw invalid();
            return prompt;
        } catch (CharacterCodingException | JacksonException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.assistant_prompt");
        }
    }

    public static void requireSize(long size) {
        if (size > MAX_BYTES) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.assistant_prompt");
    }
}
