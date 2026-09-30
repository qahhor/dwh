package com.smartup24.cms.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 3.1: a field error names the catalog key of its text, as the problem itself does. */
class FieldErrorItemTest {

    @Test
    @DisplayName("a keyed item carries its key as the message until it is rendered")
    void keyedItemCarriesItsKey() {
        FieldErrorItem item = FieldErrorItem.keyed("state", "invalid", "error.field.one_of", Map.of("values", "A, P"));

        assertThat(item.message()).isEqualTo("error.field.one_of");
        assertThat(item.messageKey()).isEqualTo("error.field.one_of");
        assertThat(item.params()).containsEntry("values", "A, P");

        FieldErrorItem rendered = item.withMessage("Allowed values: A, P");
        assertThat(rendered.message()).isEqualTo("Allowed values: A, P");
        assertThat(rendered.messageKey()).isEqualTo("error.field.one_of");
        assertThat(rendered.at("options.state").field()).isEqualTo("options.state");
    }

    @Test
    @DisplayName("an item without parameters or without a key leaves them out")
    void emptyParametersAndWrittenTextAreLeftOut() {
        assertThat(FieldErrorItem.keyed("q", "invalid", "error.field.required").params())
                .isNull();
        assertThat(FieldErrorItem.keyed("q", "invalid", "error.field.required", Map.of())
                        .params())
                .isNull();
        FieldErrorItem written = new FieldErrorItem("name", "NotBlank", "must not be blank");
        assertThat(written.messageKey()).isNull();
        assertThat(written.params()).isNull();
    }
}
