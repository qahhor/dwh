package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.ms.task.api.ProjectMemberView;
import com.smartup24.cms.instance.ms.task.api.TaskCommentView;
import com.smartup24.cms.instance.ms.task.api.TaskFileView;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository.ProjectMemberRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskCommentRepository.CommentRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskFileRepository.TaskFileRecord;
import java.util.List;
import java.util.function.Function;

/**
 * Repository rows to the DTOs of {@code ms.task.api}: the rows stay inside the module (plan 10/10, item 3.2). The
 * records of the tasks, projects, statuses and types are the general runtime's (ADR-0032, 6.2).
 */
final class MsTaskViews {

    private MsTaskViews() {}

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

    static <R, V> List<V> all(List<R> rows, Function<R, V> view) {
        return rows.stream().map(view).toList();
    }
}
