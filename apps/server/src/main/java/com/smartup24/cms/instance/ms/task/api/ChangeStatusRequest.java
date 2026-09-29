package com.smartup24.cms.instance.ms.task.api;

public record ChangeStatusRequest(Long statusId, Long expectedRevision) {
    public ChangeStatusRequest(Long statusId) {
        this(statusId, null);
    }
}
