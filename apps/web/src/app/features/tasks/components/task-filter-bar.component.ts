import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { TaskStatus } from '@core/models/task.models';
import { TaskStatusFilter, statusFilter } from '../tasks.models';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { TaskLookupsService } from '../services/task-lookups.service';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { I18nService } from '@core/services/i18n.service';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';

export type TaskPreset = 'all' | 'my' | 'executor' | 'observer' | 'reported' | 'overdue';

@Component({
  selector: 'app-task-filter-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTRadioGroupComponent,
    SMTInputComponent,
    TranslatePipe,
    SMTSelectComponent,
    SMTDataSelectComponent,
    SMTButtonComponent,
  ],
  templateUrl: './task-filter-bar.component.html',
  styleUrl: './task-filter-bar.component.css',
})
export class TaskFilterBarComponent {
  /** The project picker's source and the projects the rows already named. */
  readonly lookups = inject(TaskLookupsService);
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  readonly activePreset = input<TaskPreset>('all');
  readonly searchQuery = input('');
  readonly statusFilterMode = input<TaskStatusFilter>('active');
  readonly statuses = input<TaskStatus[]>([]);
  readonly selectedProjectId = input<number | null>(null);
  readonly selectedPriority = input('');
  readonly hasActiveFilters = input(false);

  readonly activePresetChange = output<TaskPreset>();
  readonly searchQueryChange = output<string>();
  readonly searchApply = output<void>();
  readonly searchClear = output<void>();
  readonly statusFilterModeChange = output<TaskStatusFilter>();
  readonly selectedProjectIdChange = output<number | null>();
  readonly selectedPriorityChange = output<string>();
  readonly resetFilters = output<void>();

  readonly moreFiltersOpen = signal(false);
  readonly secondaryFilterCount = computed(
    () =>
      Number(this.activePreset() !== 'all') +
      Number(this.selectedProjectId() !== null) +
      Number(!!this.selectedPriority()),
  );

  private readonly presetMemo = optionsMemo<SMTRadioOption<TaskPreset>[]>();

  private readonly statusMemo = optionsMemo<SMTSelectOption<TaskStatusFilter>[]>();

  private readonly priorityMemo = optionsMemo<SMTSelectOption<string>[]>();

  /** Typing searches after a pause; emptying the field (its clear button) drops the search at once. */
  onSearchValue(value: SMTInputValue): void {
    const query = value === null ? '' : String(value);
    if (query === '') {
      this.searchClear.emit();
    } else {
      this.searchQueryChange.emit(query);
    }
  }

  onPresetClick(preset: TaskPreset) {
    this.activePresetChange.emit(preset);
  }

  presetOptions(): SMTRadioOption<TaskPreset>[] {
    return this.presetMemo([this.optionText.currentLang()], () => [
      { value: 'all', label: this.optionText.translate('tasks.filter_preset_all'), icon: 'dashboard' },
      { value: 'my', label: this.optionText.translate('tasks.filter_preset_my'), icon: 'person' },
      { value: 'executor', label: this.optionText.translate('tasks.filter_preset_executor'), icon: 'group' },
      { value: 'observer', label: this.optionText.translate('tasks.filter_preset_observer'), icon: 'visibility' },
      { value: 'reported', label: this.optionText.translate('tasks.filter_preset_reported'), icon: 'assignment_ind' },
      { value: 'overdue', label: this.optionText.translate('tasks.filter_preset_overdue'), icon: 'error' },
    ]);
  }

  /** Active, all, then each status with its colour mark. */
  statusOptions(): SMTSelectOption<TaskStatusFilter>[] {
    return this.statusMemo([this.statuses(), this.optionText.currentLang()], () => [
      {
        id: 'active',
        label: this.optionText.translate('iam.common.active_plural'),
      },
      {
        id: 'all',
        label: this.optionText.translate('common.all'),
      },
      ...this.statuses().map((status) => ({
        id: statusFilter(status.code),
        label: status.name,
        color: status.color || undefined,
      })),
    ]);
  }

  /** Priorities, most urgent first; no choice means every priority. */
  priorityOptions(): SMTSelectOption<string>[] {
    return this.priorityMemo([this.optionText.currentLang()], () => [
      { id: 'critical', label: this.optionText.translate('tasks.common.critical') },
      { id: 'high', label: this.optionText.translate('task.priority.high') },
      { id: 'medium', label: this.optionText.translate('tasks.common.medium') },
      { id: 'low', label: this.optionText.translate('task.priority.low') },
    ]);
  }
}
