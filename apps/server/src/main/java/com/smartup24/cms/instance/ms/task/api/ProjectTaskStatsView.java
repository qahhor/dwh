package com.smartup24.cms.instance.ms.task.api;

/** The task counts of one project over the tasks the viewer may see. */
public record ProjectTaskStatsView(Long projectId, int totalTasks, int activeTasks, int doneTasks) {}
