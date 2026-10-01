import { Injectable, ResourceRef, inject, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { TaskStatus, TaskType } from '@core/models/task.models';
import { catchError, map, of, tap } from 'rxjs';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';

@Injectable({
  providedIn: 'root',
})
export class TaskDictionariesService {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly isSettingsModalOpen = signal<boolean>(false);

  // A failed read answers with the list already on screen, so a reload that fails keeps it.
  private readonly statusesResource: ResourceRef<TaskStatus[]> = rxResource({
    defaultValue: [],
    stream: () =>
      this.api.get<TaskStatus[]>('/tasks/statuses').pipe(
        map((res) => res || []),
        catchError(() => of(untracked(this.statusesResource.value))),
      ),
  });
  private readonly typesResource: ResourceRef<TaskType[]> = rxResource({
    defaultValue: [],
    stream: () =>
      this.api.get<TaskType[]>('/tasks/types').pipe(
        map((res) => res || []),
        catchError(() => of(untracked(this.typesResource.value))),
      ),
  });

  /** Writable: a new order is shown before the server confirms it. */
  readonly statuses = this.statusesResource.value;
  readonly taskTypes = this.typesResource.value;
  settingsTab: 'types' | 'statuses' = 'types';
  dictionaryDeleteTarget: { kind: 'type' | 'status'; id: number; name: string } | null = null;

  /** The service outlives the screen, so each visit asks again; the first read is already on its way. */
  loadStatuses(): void {
    this.statusesResource.reload();
  }

  loadTypes(): void {
    this.typesResource.reload();
  }

  openSettingsModal(): void {
    this.isSettingsModalOpen.set(true);
  }

  handleCreateType(event: { code: string; name: string; icon: string; color: string }): void {
    this.api
      .post('/tasks/types', {
        code: event.code,
        name: event.name,
        icon: event.icon,
        color: event.color,
        orderNo: (this.taskTypes().length + 1) * 10,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.uiI18n.translate('tasks.dictionaries.type_added'));
          this.loadTypes();
        },
        error: (err) =>
          this.toast.error(err.error?.message || this.uiI18n.translate('tasks.dictionaries.type_add_failed')),
      });
  }

  handleCreateStatus(event: { name: string; color: string; isTerminal: boolean }): void {
    this.api
      .post('/tasks/statuses', {
        name: event.name,
        color: event.color,
        orderNo: (this.statuses().length + 1) * 10,
        isTerminal: event.isTerminal,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.uiI18n.translate('tasks.dictionaries.status_added'));
          this.loadStatuses();
        },
        error: (err) =>
          this.toast.error(err.error?.message || this.uiI18n.translate('tasks.dictionaries.status_add_failed')),
      });
  }

  /**
   * Asks before deleting a type or status and deletes from the dialog; a
   * refusal (the item is in use) is shown in the dialog, not as a toast.
   */
  handleDeleteDictionaryItem(target: { kind: 'type' | 'status'; id: number; name: string }): void {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    const isType = target.kind === 'type';
    const endpoint = isType ? `/tasks/types/${target.id}` : `/tasks/statuses/${target.id}`;
    this.modal
      .confirm({
        title: t('tasks.dictionaries.delete_title'),
        message: `${t('tasks.delete_dictionary_confirm', { kind: t(isType ? 'tasks.task_type_accusative' : 'tasks.status_accusative'), name: target.name })}\n${t('tasks.dictionaries.delete_in_use_warning')}`,
        yesLabel: t('common.delete'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () =>
          this.api.delete(endpoint, { notifyError: false }).pipe(
            tap(() => {
              if (isType) {
                this.toast.success(t('tasks.dictionaries.type_deleted'));
                this.loadTypes();
              } else {
                this.toast.success(t('tasks.dictionaries.status_deleted'));
                this.loadStatuses();
              }
            }),
          ),
        actionError: (error) =>
          problemText(error) ||
          t(isType ? 'tasks.dictionaries.type_delete_failed' : 'tasks.dictionaries.status_in_use'),
      })
      .subscribe();
  }

  handleReorderTypes(list: TaskType[]): void {
    this.taskTypes.set(list);
    this.persistTypeOrder(list);
  }

  handleReorderStatuses(list: TaskStatus[]): void {
    this.statuses.set(list);
    this.persistStatusOrder(list);
  }

  private persistStatusOrder(list: TaskStatus[]): void {
    const orderedIds = list.map((status) => status.id);
    this.api.post('/tasks/statuses/reorder', orderedIds).subscribe({
      next: () => this.toast.success(this.uiI18n.translate('tasks.dictionaries.status_order_saved')),
      error: (err) =>
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.dictionaries.reorder_failed')),
    });
  }

  private persistTypeOrder(list: TaskType[]): void {
    const orderedIds = list.map((t) => t.id);
    this.api.post('/tasks/types/reorder', orderedIds).subscribe({
      next: () => this.toast.success(this.uiI18n.translate('tasks.dictionaries.type_order_saved')),
      error: (err) =>
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.dictionaries.reorder_failed')),
    });
  }
}
