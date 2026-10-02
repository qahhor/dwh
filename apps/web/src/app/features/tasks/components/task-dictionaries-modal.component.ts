import { ChangeDetectionStrategy, Component, signal, inject, input, output } from '@angular/core';

import {
  SMTSortableActionsDirective,
  SMTSortableItemDirective,
  SMTSortableListComponent,
} from '@shared/ui-kit/components/sortable-list';
import { SMTColorInputComponent } from '@shared/ui-kit/components/forms/color-input';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { TaskStatus, TaskType } from '@core/models/task.models';
import { SMTTabBarComponent, SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';

interface NewTypeForm {
  code: string;
  name: string;
  icon: string;
  color: string;
}

interface NewStatusForm {
  name: string;
  color: string;
  terminal: boolean;
}

function emptyTypeForm(): NewTypeForm {
  return { code: '', name: '', icon: 'task_alt', color: '#2563eb' };
}

function emptyStatusForm(): NewStatusForm {
  return { name: '', color: '#0284c7', terminal: false };
}

@Component({
  selector: 'app-task-dictionaries-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTCheckboxComponent,
    SMTTabBarComponent,
    SMTColorInputComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTSortableListComponent,
    SMTSortableItemDirective,
    SMTSortableActionsDirective,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
  ],
  templateUrl: './task-dictionaries-modal.component.html',
  styleUrl: './task-dictionaries-modal.component.css',
})
export class TaskDictionariesModalComponent {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  readonly isOpen = input(false);
  readonly taskTypes = input<TaskType[]>([]);
  readonly statuses = input<TaskStatus[]>([]);

  readonly closeModal = output<void>();
  readonly createType = output<{
    code: string;
    name: string;
    icon: string;
    color: string;
  }>();
  readonly createStatus = output<{
    name: string;
    color: string;
    terminal: boolean;
  }>();
  readonly deleteItem = output<{
    kind: 'type' | 'status';
    id: number;
    name: string;
  }>();
  readonly reorderTypes = output<TaskType[]>();
  readonly reorderStatuses = output<TaskStatus[]>();

  /** The new type and status being typed; signals, since the fields' callbacks change them. */
  readonly newTypeForm = signal<NewTypeForm>(emptyTypeForm());
  readonly newStatusForm = signal<NewStatusForm>(emptyStatusForm());

  settingsTab: 'types' | 'statuses' = 'types';

  /** Rows are kept by id, so a moved row keeps its focus. */
  readonly byId = (item: { id: number }) => item.id;

  readonly nameOf = (item: { name: string }) => item.name;

  private readonly tabsMemo = optionsMemo<SMTTabItem<'types' | 'statuses'>[]>();

  patchTypeForm(patch: Partial<NewTypeForm>): void {
    this.newTypeForm.update((form) => ({ ...form, ...patch }));
  }

  patchStatusForm(patch: Partial<NewStatusForm>): void {
    this.newStatusForm.update((form) => ({ ...form, ...patch }));
  }

  /** A text field's value is typed as text, a number or null; the forms keep text. */
  text(value: SMTInputValue): string {
    return value === null ? '' : String(value);
  }

  submitType() {
    const form = this.newTypeForm();
    if (!form.code.trim() || !form.name.trim()) return;
    this.createType.emit({
      code: form.code.trim(),
      name: form.name.trim(),
      icon: form.icon.trim() || 'task_alt',
      color: form.color,
    });
    this.newTypeForm.set(emptyTypeForm());
  }

  submitStatus() {
    const form = this.newStatusForm();
    if (!form.name.trim()) return;
    this.createStatus.emit({
      name: form.name.trim(),
      color: form.color,
      terminal: form.terminal,
    });
    this.newStatusForm.set(emptyStatusForm());
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
