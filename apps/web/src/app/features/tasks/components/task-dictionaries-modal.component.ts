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
  styleUrl: './task-dictionaries-modal.component.css',
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
