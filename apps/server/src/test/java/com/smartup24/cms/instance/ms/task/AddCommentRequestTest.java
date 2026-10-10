package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.ms.task.api.AddCommentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The text of a new comment is its one text field, trimmed. */
class AddCommentRequestTest {

    @Test
    @DisplayName("The text field is trimmed")
    void resolvesTheText() {
        assertThat(new AddCommentRequest("  first  ", null).resolveText()).isEqualTo("first");
    }

    @Test
    @DisplayName("A missing or blank text gives an empty text")
    void emptyWithoutText() {
        assertThat(new AddCommentRequest(null, null).resolveText()).isEmpty();
        assertThat(new AddCommentRequest("   ", null).resolveText()).isEmpty();
    }
}
