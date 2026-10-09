package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.ms.task.api.AddCommentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The text of a new comment is the first non-blank of its two fields, trimmed. */
class AddCommentRequestTest {

    @Test
    @DisplayName("The text field wins; the second field is read only when the first is missing or blank")
    void resolvesTheText() {
        assertThat(new AddCommentRequest("  first  ", "second", null).resolveText())
                .isEqualTo("first");
        assertThat(new AddCommentRequest(null, " second ", null).resolveText()).isEqualTo("second");
        assertThat(new AddCommentRequest("   ", "second", null).resolveText()).isEqualTo("second");
    }

    @Test
    @DisplayName("Both fields missing or blank give an empty text")
    void emptyWithoutText() {
        assertThat(new AddCommentRequest(null, null, null).resolveText()).isEmpty();
        assertThat(new AddCommentRequest(" ", "  ", null).resolveText()).isEmpty();
    }
}
