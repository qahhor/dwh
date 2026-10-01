import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { TaskStatus } from '@core/models/task.models';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { TaskLookupsService } from '../services/task-lookups.service';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { I18nService } from '@core/services/i18n.service';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';

export type TaskPreset = 'all' | 'my' | 'executor' | 'observer' | 'reported' | 'overdue';

@Component({
  selector: 'app-task-filter-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTRadioGroupComponent, SMTInputComponent, TranslatePipe, SMTSelectComponent, SMTDataSelectComponent],
  template: `
    <div class="toolbar">
      <div class="toolbar-left-row">
        <!-- Smart View Presets -->
        <smt-radio-group
          smtAppearance="chips"
          class="preset-filter"
          [options]="presetOptions()"
          [value]="activePreset()"
          [smtAriaLabel]="'tasks.filter.quick_filters' | t"
          (valueChange)="onPresetClick($event ?? activePreset())"
        />

        <label class="sr-only" for="task-search">{{ 'tasks.filter.search_tasks' | t }}</label>
        <smt-input
          class="search-field"
          smtFieldId="task-search"
          name="taskSearch"
          type="search"
          smtIcon="search"
          smtSize="sm"
          clearable
          [placeholder]="'projects.common.search_placeholder' | t"
          [value]="searchQuery()"
          (valueChange)="onSearchValue($event)"
          (keydown.enter)="searchApply.emit(); $event.preventDefault()"
        />
      </div>

      <div class="toolbar-controls">
        <!-- Status Filter Tabs -->
        <smt-radio-group
          smtAppearance="chips"
          class="status-filter"
          [options]="statusOptions()"
          [value]="statusFilterMode()"
          [smtAriaLabel]="'tasks.filter.filter_by_status' | t"
          (valueChange)="statusFilterModeChange.emit($event ?? statusFilterMode())"
        />

        <!-- Project Filter: searched on the server, 20 projects a page (plan 10/10, item 3.5). -->
        <label class="sr-only" for="task-project-filter">{{ 'tasks.filter.filter_by_project' | t }}</label>
        <smt-data-select
          class="project-filter"
          smtTriggerId="task-project-filter"
          [source]="lookups.projects"
          [knownRows]="lookups.knownProjectRows()"
          [value]="selectedProjectId()"
          (valueChange)="selectedProjectIdChange.emit($event)"
          [placeholder]="'tasks.filter.all_projects' | t"
          [searchPlaceholder]="'tasks.search_project' | t"
          [emptyLabel]="'tasks.filter.all_projects' | t"
        />

        <!-- Priority Filter -->
        <label class="sr-only" for="task-priority-filter">{{ 'tasks.filter.filter_by_priority' | t }}</label>
        <smt-select
          class="priority-filter"
          smtTriggerId="task-priority-filter"
          [value]="selectedPriority() || null"
          (valueChange)="selectedPriorityChange.emit($event ?? '')"
          [options]="priorityOptions()"
          [placeholder]="'tasks.filter.all_priorities' | t"
          [emptyLabel]="'tasks.filter.all_priorities' | t"
        ></smt-select>

        @if (hasActiveFilters()) {
          <button
            type="button"
            class="reset-filters-btn"
            [attr.aria-label]="'tasks.common.reset_all_filters' | t"
            (click)="resetFilters.emit()"
            [title]="'tasks.common.reset_all_filters' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">filter_alt_off</span>
          </button>
        }
      </div>
    </div>
  `,
  styleUrl: './task-filter-bar.component.css',
})
export class TaskFilterBarComponent {
  /** The project picker's source and the projects the rows already named. */
  readonly lookups = inject(TaskLookupsService);
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  readonly activePreset = input<TaskPreset>('all');
  readonly searchQuery = input('');
  readonly statusFilterMode = input<'active' | 'all' | number>('active');
  readonly statuses = input<TaskStatus[]>([]);
  readonly selectedProjectId = input<number | null>(null);
  readonly selectedPriority = input('');
  readonly hasActiveFilters = input(false);

  readonly activePresetChange = output<TaskPreset>();
  readonly searchQueryChange = output<string>();
  readonly searchApply = output<void>();
  readonly searchClear = output<void>();
  readonly statusFilterModeChange = output<'active' | 'all' | number>();
  readonly selectedProjectIdChange = output<number | null>();
  readonly selectedPriorityChange = output<string>();
  readonly resetFilters = output<void>();

  private readonly presetMemo = optionsMemo<SMTRadioOption<TaskPreset>[]>();

  private readonly statusMemo = optionsMemo<SMTRadioOption<'active' | 'all' | number>[]>();

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
  statusOptions(): SMTRadioOption<'active' | 'all' | number>[] {
    return this.statusMemo([this.statuses(), this.optionText.currentLang()], () => [
      {
        value: 'active',
        label: this.optionText.translate('iam.common.active_plural'),
        title: this.optionText.translate('tasks.filter.only_active'),
      },
      {
        value: 'all',
        label: this.optionText.translate('common.all'),
        title: this.optionText.translate('tasks.filter.include_completed'),
      },
      ...this.statuses().map((status) => ({ value: status.id, label: status.name, color: status.color || undefined })),
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
