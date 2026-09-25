import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { ToastService } from '../../../core/services/toast.service';
import { I18nService } from '../../../core/services/i18n.service';
import { TaskStatus, TaskType } from '../../../core/models/task.models';
import { tap } from 'rxjs';
import { SMTModalService } from '../../../shared/ui-kit/components/modal';
import { problemText } from '../../../shared/ui/problem-text';

@Injectable({
  providedIn: 'root'
})
export class TaskDictionariesService {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly statuses = signal<TaskStatus[]>([]);
  readonly taskTypes = signal<TaskType[]>([]);
  readonly isSettingsModalOpen = signal<boolean>(false);
  settingsTab: 'types' | 'statuses' = 'types';
  newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
  newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };
  dictionaryDeleteTarget: { kind: 'type' | 'status'; id: number; name: string } | null = null;

  loadStatuses(): void {
    this.api.get<TaskStatus[]>('/tasks/statuses').subscribe({
      next: res => this.statuses.set(res || []),
      error: () => {}
    });
  }

  loadTypes(): void {
    this.api.get<TaskType[]>('/tasks/types').subscribe({
      next: res => this.taskTypes.set(res || []),
      error: () => {}
    });
  }

  openSettingsModal(): void {
    this.isSettingsModalOpen.set(true);
  }

  handleCreateType(event: { code: string; name: string; icon: string; color: string }): void {
    this.api.post('/tasks/types', {
      code: event.code,
      name: event.name,
      icon: event.icon,
      color: event.color,
      orderNo: (this.taskTypes().length + 1) * 10
    }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.tip_zadachi_dobavlen'));
        this.loadTypes();
      },
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_dobavleniya_tipa'))
    });
  }

  handleCreateStatus(event: { name: string; color: string; isTerminal: boolean }): void {
    this.api.post('/tasks/statuses', {
      name: event.name,
      color: event.color,
      orderNo: (this.statuses().length + 1) * 10,
      isTerminal: event.isTerminal
    }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.status_zadachi_dobavlen'));
        this.loadStatuses();
      },
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_dobavleniya_statusa'))
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
    this.modal.confirm({
      title: t('tasks.udalenie_elementa_spravochnika'),
      message: `${t('tasks.delete_dictionary_confirm', { kind: t(isType ? 'tasks.task_type_accusative' : 'tasks.status_accusative'), name: target.name })}\n${t('tasks.udalenie_budet_otkloneno_esli_element_uzhe_ispol')}`,
      yesLabel: t('common.delete'),
      noLabel: t('common.cancel'),
      destructive: true,
      action: () => this.api.delete(endpoint, { notifyError: false }).pipe(
        tap(() => {
          if (isType) {
            this.toast.success(t('tasks.tip_zadachi_udalen'));
            this.loadTypes();
          } else {
            this.toast.success(t('tasks.status_udalen'));
            this.loadStatuses();
          }
        })
      ),
      actionError: error => problemText(error)
        || t(isType ? 'tasks.oshibka_udaleniya_tipa' : 'tasks.nelzya_udalit_status_privyazannyy_k_zadacham')
    }).subscribe();
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
    const orderedIds = list.map(status => status.id);
    this.api.post('/tasks/statuses/reorder', orderedIds).subscribe({
      next: () => this.toast.success(this.uiI18n.translate('tasks.poryadok_statusov_sohranen')),
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_izmeneniya_poryadka'))
    });
  }

  private persistTypeOrder(list: TaskType[]): void {
    const orderedIds = list.map(t => t.id);
    this.api.post('/tasks/types/reorder', orderedIds).subscribe({
      next: () => this.toast.success(this.uiI18n.translate('tasks.poryadok_tipov_zadach_sohranen')),
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_izmeneniya_poryadka'))
    });
  }
}
