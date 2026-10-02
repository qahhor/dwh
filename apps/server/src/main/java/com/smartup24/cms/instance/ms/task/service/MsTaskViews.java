package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.ms.task.api.ProjectMemberView;
import com.smartup24.cms.instance.ms.task.api.TaskCommentView;
import com.smartup24.cms.instance.ms.task.api.TaskFileView;
import com.smartup24.cms.instance.ms.task.api.TaskListFilters;
import com.smartup24.cms.instance.ms.task.api.TaskMemberView;
import com.smartup24.cms.instance.ms.task.api.TaskView;
import com.smartup24.cms.instance.ms.task.repository.LegacyTaskFilters;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository.ProjectMemberRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskCommentRepository.CommentRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskFileRepository.TaskFileRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository.TaskMemberRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import java.util.List;
import java.util.function.Function;

/** Repository rows to the DTOs of {@code ms.task.api}: the rows stay inside the module (plan 10/10, item 3.2). */
final class MsTaskViews {

    private MsTaskViews() {}

    static TaskView task(TaskRecord task) {
        return new TaskView(
                task.id(),
                task.projectId(),
                task.projectName(),
                task.parentTaskId(),
                task.title(),
                task.descriptionMarkdown(),
                task.statusId(),
                task.priority(),
                task.reporterId(),
                task.attributes(),
                task.beginTime(),
                task.endTime(),
                task.resolvedTime(),
                task.createdAt(),
                task.modifiedAt(),
                task.createdBy(),
                task.modifiedBy(),
                task.revision());
    }

    static TaskMemberView member(TaskMemberRecord member) {
        return new TaskMemberView(
                member.taskId(),
                member.userId(),
                member.userName(),
                member.userLogin(),
                member.userEmail(),
                member.involveKind(),
                member.isViewed());
    }

    static TaskFileView file(TaskFileRecord file) {
        return new TaskFileView(file.fileId(), file.fileName(), file.sizeBytes(), file.mimeType(), file.createdAt());
    }

    static ProjectMemberView projectMember(ProjectMemberRecord member) {
        return new ProjectMemberView(
                member.projectId(), member.userId(), member.userName(), member.userEmail(), member.accessKind());
    }

    static TaskCommentView comment(CommentRecord comment) {
        return new TaskCommentView(
                comment.id(),
                comment.taskId(),
                comment.userId(),
                comment.textMarkdown(),
                comment.fileIds(),
                comment.createdAt(),
                comment.userName(),
                comment.userLogin());
    }

    static LegacyTaskFilters filters(TaskListFilters filters) {
        return filters == null
                ? LegacyTaskFilters.none()
                : new LegacyTaskFilters(
                        filters.projectId(),
                        filters.statusId(),
                        filters.priority(),
                        filters.hideTerminal(),
                        filters.assignedUserId(),
                        filters.memberRole(),
                        filters.reporterId(),
                        filters.overdue());
    }

    static <R, V> List<V> all(List<R> rows, Function<R, V> view) {
        return rows.stream().map(view).toList();
    }
}
