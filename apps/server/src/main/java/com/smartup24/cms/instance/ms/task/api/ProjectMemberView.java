package com.smartup24.cms.instance.ms.task.api;

/** A member of a project with read (R) or write (W) access. */
public record ProjectMemberView(Long projectId, Long userId, String userName, String userEmail, String accessKind) {}
