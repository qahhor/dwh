import { ChangeDetectionStrategy, Component, input, output, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiMarkdownViewComponent } from '@shared/ui/ui-markdown-view.component';
import { UiFileUploadComponent } from '@shared/ui/ui-file-upload.component';
import { UiRecordHistoryComponent } from '@shared/ui/ui-record-history.component';
import { CustomField } from '@core/models/custom-field.models';
import { Task, Project, TaskStatus, TaskType, TaskMember, TaskComment, TaskFile } from '@core/models/task.models';
import { safeNumericRecordId } from '@core/services/search-target';
import { groupMembersByRole, GroupedTaskMembers } from '../tasks.models';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { RecordAttributes } from '../tasks.models';

@Component({
  selector: 'app-task-detail-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTAvatarComponent,
    SMTSelectComponent,
    SMTTextareaComponent,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    UiMarkdownViewComponent,
    UiFileUploadComponent,
    UiRecordHistoryComponent,
    DatePipe,
  ],
  templateUrl: './task-detail-modal.component.html',
  styleUrl: './task-detail-modal.component.css',
})
export class TaskDetailModalComponent {
  private readonly i18n = inject(I18nService);

  readonly isOverdue = input.required<(endTime: string | null | undefined, statusId: number) => boolean>();
  readonly getTypeColor = input.required<(task: Task) => string>();
  readonly getTypeBg = input.required<(task: Task) => string>();
  readonly getTypeIcon = input.required<(task: Task) => string>();
  readonly getTypeLabel = input.required<(task: Task) => string>();
  readonly getStatusName = input.required<(statusId: number | null | undefined) => string>();
  readonly getStatusColor = input.required<(statusId: number | null | undefined) => string>();
  readonly getPriorityLabel = input.required<(priority: string) => string>();
  readonly getProjectName = input.required<(projectId: number | null | undefined) => string | null>();

  readonly isOpen = input(false);
  readonly selectedTask = input<Task | null>(null);
  readonly routeRecordId = input<string | null>(null);
  readonly detailRecordId = input<string | null>(null);
  readonly detailLoading = input(false);
  readonly detailLoadError = input(false);
  readonly detailNotFound = input(false);
  readonly taskFiles = input<TaskFile[]>([]);
  readonly comments = input<TaskComment[]>([]);
  readonly commentsLoading = input(false);
  /** More comments follow the ones on screen (plan item 3.5). */
  readonly commentsHasMore = input(false);
  readonly commentsLoadingMore = input(false);
  readonly commentsLoadError = input(false);
  readonly taskMembers = input<TaskMember[]>([]);
  readonly taskCustomFields = input<CustomField[]>([]);
  readonly statuses = input<TaskStatus[]>([]);
  readonly projects = input<Project[]>([]);
  readonly taskTypes = input<TaskType[]>([]);
  readonly commentDraft = input('');
  readonly isCommentSubmitting = input(false);
  readonly canCreateTask = input(false);
  readonly canUpdateTask = input(false);
  readonly canCommentTask = input(false);
  readonly taskAncestors = input<Task[]>([]);
  readonly taskSubtasks = input<Task[]>([]);

  readonly closeModal = output<void>();
  readonly retryTaskDetails = output<void>();
  readonly openTaskDetails = output<Task>();
  readonly openAddSubtask = output<Task>();
  readonly openEditModal = output<Task>();
  readonly statusChange = output<{
    taskId: number;
    statusId: number;
  }>();
  readonly fileAttached = output<TaskFile>();
  readonly fileRemoved = output<TaskFile>();
  readonly retryComments = output<void>();
  readonly loadMoreComments = output<void>();
  readonly commentDraftChange = output<string>();
  readonly submitComment = output<void>();

  readonly safeRecordId = safeNumericRecordId;

  private readonly statusMemo = optionsMemo<SMTSelectOption<number>[]>();

  /** Statuses as smt-select options; the same array while the statuses stay the same. */
  statusOptions(): SMTSelectOption<number>[] {
    return this.statusMemo([this.statuses()], () =>
      this.statuses().map((status) => ({ id: status.id, label: status.name })),
    );
  }

  onStatusChange(taskId: number, statusId: number | null): void {
    if (statusId !== null) this.statusChange.emit({ taskId, statusId });
  }

  get groupedMembers(): GroupedTaskMembers {
    return groupMembersByRole(this.taskMembers());
  }

  getInvolveKindLabel(kind: string | undefined): string {
    switch (kind) {
      case 'R':
        return this.i18n.translate('task.responsible');
      case 'E':
        return this.i18n.translate('tasks.ispolnitel');
      case 'O':
        return this.i18n.translate('tasks.nablyudatel');
      case 'A':
        return this.i18n.translate('tasks.avtor');
      default:
        return this.i18n.translate('tasks.uchastnik');
    }
  }

  hasAttributes(attrs: RecordAttributes): attrs is Record<string, unknown> {
    if (!attrs || typeof attrs !== 'object') return false;
    const keys = Object.keys(attrs).filter((k) => k !== 'task_type');
    return keys.length > 0;
  }

  formatAttributes(attrs: RecordAttributes): Array<{ key: string; value: string }> {
    if (!this.hasAttributes(attrs)) return [];
    const fields = this.taskCustomFields();
    return Object.entries(attrs)
      .filter(([k]) => k !== 'task_type')
      .map(([k, v]) => {
        const field = fields.find((f) => f.code === k);
        const keyLabel = field ? field.name : k;
        let valueStr = String(v ?? '');
        if (field?.fieldType === 'boolean') {
          valueStr = v === true || v === 'true' ? this.i18n.translate('common.yes') : this.i18n.translate('common.no');
        }
        return { key: keyLabel, value: valueStr };
      });
  }
}
