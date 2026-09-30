package com.smartup24.cms.instance.common.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Plan 10/10, item 3.11: JSON columns are written and read by one helper that never loses data silently. */
class JsonColumnsTest {

    private final JsonColumns columns = new JsonColumns(JsonMapper.shared(), "probe_table");

    @Test
    @DisplayName("3.11: a map round-trips; null is the empty object and a blank column the empty map")
    void roundTrip() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", "A-1");
        value.put("rank", 3);

        String json = columns.object(value);

        assertThat(json).isEqualTo("{\"code\":\"A-1\",\"rank\":3}");
        assertThat(columns.readObject(json)).isEqualTo(value);
        assertThat(columns.object(null)).isEqualTo("{}");
        assertThat(columns.readObject(null)).isEmpty();
        assertThat(columns.readObject("  ")).isEmpty();
        assertThat(columns.write(List.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
        assertThat(columns.read("[1,2]", new TypeReference<List<Integer>>() {})).containsExactly(1, 2);
    }

    @Test
    @DisplayName("3.11: a stored document that cannot be read is an error naming the table, never an empty map")
    void unreadableDocumentIsAnError() {
        assertThatThrownBy(() -> columns.readObject("{not json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe_table");
        assertThatThrownBy(() -> columns.readObject("[1,2]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe_table");
    }

    @Test
    @DisplayName("3.11: a value that cannot be written is an error, never an empty object")
    void unwritableValueIsAnError() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);

        assertThatThrownBy(() -> columns.object(cyclic))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe_table");
    }
}
