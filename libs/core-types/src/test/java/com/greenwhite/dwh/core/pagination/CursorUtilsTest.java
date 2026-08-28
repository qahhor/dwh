package com.greenwhite.dwh.core.pagination;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CursorUtilsTest {

    @Test
    void shouldEncodeAndDecodeCursorCorrectly() {
        String raw = "2026-08-28T10:00:00Z:1045";
        String encoded = CursorUtils.encode(raw);

        assertThat(encoded).isNotNull().isNotBlank();
        assertThat(CursorUtils.decode(encoded)).isEqualTo(raw);
    }

    @Test
    void shouldHandleNullAndEmptyGracefully() {
        assertThat(CursorUtils.encode(null)).isNull();
        assertThat(CursorUtils.encode("")).isNull();
        assertThat(CursorUtils.decode(null)).isNull();
        assertThat(CursorUtils.decode("invalid-base64-!!!")).isNull();
    }
}
