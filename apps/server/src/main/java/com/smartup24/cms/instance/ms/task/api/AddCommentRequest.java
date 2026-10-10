package com.smartup24.cms.instance.ms.task.api;

import java.util.List;
import java.util.UUID;

/** A new comment: its Markdown text and the files attached to it. */
public record AddCommentRequest(String textMarkdown, List<UUID> fileIds) {
    public String resolveText() {
        return textMarkdown == null ? "" : textMarkdown.trim();
    }
}
