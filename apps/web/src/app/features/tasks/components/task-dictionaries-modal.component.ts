import { ChangeDetectionStrategy, Component, signal, inject, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import {
  SMTSortableActionsDirective,
  SMTSortableItemDirective,
  SMTSortableListComponent,
} from '@shared/ui-kit/components/sortable-list';
import { SMTColorInputComponent, SMTColorInputValueAccessor } from '@shared/ui-kit/components/forms/color-input';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { TaskStatus, TaskType } from '@core/models/task.models';
import { SMTTabBarComponent, SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTCheckboxComponent, SMTCheckboxValueAccessor } from '@shared/ui-kit/components/forms/checkbox';

@Component({
  selector: 'app-task-dictionaries-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTCheckboxComponent,
    SMTCheckboxValueAccessor,
    SMTTabBarComponent,
    SMTColorInputComponent,
    SMTColorInputValueAccessor,
    SMTControlComponent,
    SMTInputComponent,
    SMTInputValueAccessor,
    FormsModule,
    SMTSortableListComponent,
    SMTSortableItemDirective,
    SMTSortableActionsDirective,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
  ],
  templateUrl: './task-dictionaries-modal.component.html',
  styles: [
    `
      .settings-modal-content {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .settings-tabs {
        display: flex;
        background-color: var(--bg-hover);
        border: 1px solid var(--border-color);
        border-radius: var(--radius-sm);
        padding: 2px;
        gap: 2px;
      }
      .tab-pane {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .dict-list {
        display: flex;
        flex-direction: column;
        gap: 6px;
        max-height: 220px;
        overflow-y: auto;
      }
      .dict-item-info {
        display: flex;
        align-items: center;
        gap: 6px;
      }
      .dict-ico {
        font-size: 16px;
      }
      .dict-name {
        font-weight: 500;
        color: var(--text-main);
      }
      .sys-badge {
        font-size: 10px;
        background-color: rgba(99, 102, 241, 0.1);
        color: var(--primary);
        padding: 1px 4px;
        border-radius: 3px;
      }
      .term-badge {
        font-size: 10px;
        background-color: rgba(16, 185, 129, 0.1);
        color: var(--success);
        padding: 1px 4px;
        border-radius: 3px;
      }
      .status-dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        display: inline-block;
      }
      .mini-del-btn {
        border: none;
        background: transparent;
        color: var(--danger);
        cursor: pointer;
        padding: 2px;
        display: flex;
      }
      .mini-del-btn .material-symbols-outlined {
        font-size: 16px;
      }
      .add-dict-box {
        background-color: var(--bg-hover);
        border: 1px dashed var(--border-color);
        border-radius: var(--radius-sm);
        padding: 10px;
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .add-dict-title {
        font-size: 11px;
        font-weight: 600;
        color: var(--text-muted);
        margin: 0;
      }
      .form-grid-3 {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 10px;
      }
      .dict-form-field,
      .color-field {
        grid-column: 1 / -1;
      }
      .clean-label {
        font-size: 11px;
        font-weight: 500;
        color: var(--text-muted);
      }
      .terminal-toggle-label {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 11px;
        color: var(--text-main);
        cursor: pointer;
      }
      .add-dict-actions {
        display: flex;
        justify-content: flex-end;
      }
      .dictionary-delete-body {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .dictionary-delete-body p {
        margin: 0;
      }
      .dictionary-delete-body span {
        color: var(--text-muted);
        font-size: 12px;
      }
      .font-mono {
        font-family: ui-monospace, monospace;
      }
      .text-muted {
        color: var(--text-muted);
      }
      .text-xs {
        font-size: 11px;
      }
    `,
  ],
})
export class TaskDictionariesModalComponent {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  readonly isOpen = input(false);
  readonly taskTypes = input<TaskType[]>([]);
  readonly statuses = input<TaskStatus[]>([]);

  readonly close = output<void>();
  readonly createType = output<{
    code: string;
    name: string;
    icon: string;
    color: string;
  }>();
  readonly createStatus = output<{
    name: string;
    color: string;
    isTerminal: boolean;
  }>();
  readonly deleteItem = output<{
    kind: 'type' | 'status';
    id: number;
    name: string;
  }>();
  readonly reorderTypes = output<TaskType[]>();
  readonly reorderStatuses = output<TaskStatus[]>();

  settingsTab: 'types' | 'statuses' = 'types';
  newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#2563eb' };
  newStatusForm = { name: '', color: '#0284c7', isTerminal: false };

  /** Rows are kept by id, so a moved row keeps its focus. */
  readonly byId = (item: { id: number }) => item.id;

  readonly nameOf = (item: { name: string }) => item.name;

  private readonly tabsMemo = optionsMemo<SMTTabItem<'types' | 'statuses'>[]>();

  submitType() {
    if (!this.newTypeForm.code.trim() || !this.newTypeForm.name.trim()) return;
    this.createType.emit({
      code: this.newTypeForm.code.trim(),
      name: this.newTypeForm.name.trim(),
      icon: this.newTypeForm.icon.trim() || 'task_alt',
      color: this.newTypeForm.color,
    });
    this.newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#2563eb' };
  }

  submitStatus() {
    if (!this.newStatusForm.name.trim()) return;
    this.createStatus.emit({
      name: this.newStatusForm.name.trim(),
      color: this.newStatusForm.color,
      isTerminal: this.newStatusForm.isTerminal,
    });
    this.newStatusForm = { name: '', color: '#0284c7', isTerminal: false };
  }

  /** Asks the page to delete; the page confirms it first. */
  requestDelete(kind: 'type' | 'status', id: number, name: string) {
    this.deleteItem.emit({ kind, id, name });
  }

  dictionaryTabs(): SMTTabItem<'types' | 'statuses'>[] {
    return this.tabsMemo([this.tabText.currentLang(), this.taskTypes().length, this.statuses().length], () => [
      {
        value: 'types',
        label: this.tabText.translate('tasks.task_types_count', { count: this.taskTypes().length }),
        id: 'task-types-tab',
        panelId: 'task-types-panel',
      },
      {
        value: 'statuses',
        label: this.tabText.translate('tasks.task_statuses_count', { count: this.statuses().length }),
        id: 'task-statuses-tab',
        panelId: 'task-statuses-panel',
      },
    ]);
  }
}
