package com.smartup24.cms.instance.ms.task.api;

import java.util.List;
import java.util.UUID;

/** A new comment; {@code commentMarkdown} is the older name of {@code textMarkdown}. */
public record AddCommentRequest(String textMarkdown, String commentMarkdown, List<UUID> fileIds) {
    public String resolveText() {
        if (textMarkdown != null && !textMarkdown.isBlank()) {
            return textMarkdown.trim();
        }
        if (commentMarkdown != null && !commentMarkdown.isBlank()) {
            return commentMarkdown.trim();
        }
        return "";
    }
}
