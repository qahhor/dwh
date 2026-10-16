package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.*;

import com.smartup24.cms.instance.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StudioAssistantInputTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StudioAssistantInput input = new StudioAssistantInput(json);

    @Test
    void acceptsBoundedUnicodeAndTrimsWhitespace() {
        String prompt = "\uD83D\uDE00".repeat(2000);
        assertThat(input.parse(json.writeValueAsBytes(java.util.Map.of("prompt", " " + prompt + " "))))
                .isEqualTo(prompt);
    }

    @Test
    void preservesMultilinePromptsAndRefusesUnpairedUnicodeAndUnknownOnlyKeys() {
        assertThat(input.parse(json.writeValueAsBytes(java.util.Map.of("prompt", "Form\n\tfields"))))
                .isEqualTo("Form\n\tfields");
        for (String source :
                new String[] {"{\"other\":\"task\"}", "{\"prompt\":\"x\\ud800\"}", "{\"prompt\":\"x\\udfff\"}"}) {
            assertThatThrownBy(() -> input.parse(source.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void rejectsUnknownDuplicateTrailingEmptyInvalidUtf8AndOversizedInput() {
        for (String text : new String[] {
            "{}",
            "[]",
            "null",
            "{\"prompt\":1}",
            "{\"prompt\":\" \"}",
            "{\"prompt\":\"x\",\"rows\":[]}",
            "{\"prompt\":\"x\",\"prompt\":\"y\"}",
            "{\"prompt\":\"x\"}{}"
        }) {
            assertThatThrownBy(() -> input.parse(text.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> input.parse(new byte[] {(byte) 0xc3, 0x28})).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> input.parse(json.writeValueAsBytes(java.util.Map.of("prompt", "x".repeat(2001)))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> input.parse(new byte[StudioAssistantInput.MAX_BYTES + 1]))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> input.parse(json.writeValueAsBytes(java.util.Map.of("prompt", "x\u0000"))))
                .isInstanceOf(ApiException.class);
    }
}
