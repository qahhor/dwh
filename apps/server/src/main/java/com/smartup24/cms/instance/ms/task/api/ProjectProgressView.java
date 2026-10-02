package com.smartup24.cms.instance.ms.task.api;

/**
 * The progress of a project over the tasks the viewer may see (ADR-0013): every task, the closed ones (in a terminal
 * status) and their share in per cent.
 */
public record ProjectProgressView(long projectId, int totalTasks, int doneTasks, int progress) {}
