package com.smartup24.cms.instance.ms.task.api;

import java.util.List;

/** The task card: the task with its participants, subtasks, ancestor chain and files. */
public record TaskDetail(
        TaskView task,
        List<TaskMemberView> members,
        List<TaskView> subtasks,
        List<TaskView> ancestors,
        List<TaskFileView> files) {}
