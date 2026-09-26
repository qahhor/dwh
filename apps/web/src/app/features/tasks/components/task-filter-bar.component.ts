import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { Project, TaskStatus } from '../../../core/models/task.models';
import { SMTSelectComponent, SMTSelectOption } from '../../../shared/ui-kit/components/forms/select';
import { ProjectOptionsPipe } from './project-options.pipe';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../core/services/i18n.service';
import { SMTInputComponent, SMTInputValue } from '../../../shared/ui-kit/components/forms/input';

export type TaskPreset = 'all' | 'my' | 'executor' | 'observer' | 'reported' | 'overdue';

@Component({
  selector: 'app-task-filter-bar',
  standalone: true,
  imports: [
    SMTRadioGroupComponent, SMTInputComponent, CommonModule,
    TranslatePipe,
    SMTSelectComponent,
    ProjectOptionsPipe
  ],
  template: `
    <div class="toolbar">
      <div class="toolbar-left-row">
        <!-- Smart View Presets -->
        <smt-radio-group
          smtAppearance="chips"
          class="preset-filter"
          [options]="presetOptions()"
          [value]="activePreset"
          [smtAriaLabel]="'tasks.bystrye_filtry' | t"
          (valueChange)="onPresetClick($event ?? activePreset)" />

        <label class="sr-only" for="task-search">{{ 'tasks.poisk_zadach' | t }}</label>
        <smt-input
          class="search-field"
          smtFieldId="task-search"
          name="taskSearch"
          type="search"
          smtIcon="search"
          smtSize="sm"
          clearable
          [placeholder]="'projects.poisk_po_nazvaniyu_ili_opisaniyu' | t"
          [value]="searchQuery"
          (valueChange)="onSearchValue($event)"
          (keydown.enter)="searchApply.emit(); $event.preventDefault()" />
      </div>

      <div class="toolbar-controls">
        <!-- Status Filter Tabs -->
        <smt-radio-group
          smtAppearance="chips"
          class="status-filter"
          [options]="statusOptions()"
          [value]="statusFilterMode"
          [smtAriaLabel]="'tasks.filtr_po_statusu' | t"
          (valueChange)="statusFilterModeChange.emit($event ?? statusFilterMode)" />

        <!-- Project Filter -->
        <label class="sr-only" for="task-project-filter">{{ 'tasks.filtr_po_proektu' | t }}</label>
        <smt-select
          class="project-filter"
          smtTriggerId="task-project-filter"
          [value]="selectedProjectId"
          (valueChange)="selectedProjectIdChange.emit($event)"
          [options]="projects | projectOptions"
          [placeholder]="'tasks.vse_proekty' | t"
          [searchPlaceholder]="'tasks.search_project' | t"
          [emptyLabel]="'tasks.vse_proekty' | t"
        ></smt-select>

        <!-- Priority Filter -->
        <label class="sr-only" for="task-priority-filter">{{ 'tasks.filtr_po_prioritetu' | t }}</label>
        <smt-select
          class="priority-filter"
          smtTriggerId="task-priority-filter"
          [value]="selectedPriority || null"
          (valueChange)="selectedPriorityChange.emit($event ?? '')"
          [options]="priorityOptions()"
          [placeholder]="'tasks.vse_prioritety' | t"
          [emptyLabel]="'tasks.vse_prioritety' | t"
        ></smt-select>

        <button
          *ngIf="hasActiveFilters"
          type="button"
          class="reset-filters-btn"
          [attr.aria-label]="'tasks.sbrosit_vse_filtry' | t"
          (click)="resetFilters.emit()"
          [title]="'tasks.sbrosit_vse_filtry' | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">filter_alt_off</span>
        </button>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
      max-width: 100%;
    }
    .toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 8px 12px;
      max-width: 100%;
      box-sizing: border-box;
    }
    .toolbar-left-row {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
      max-width: 100%;
      min-width: 0;
    }
    .search-field { width: 260px; max-width: 100%; }

    .toolbar-controls {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      max-width: 100%;
      min-width: 0;
    }


    /* A searchable project list; wide enough for a typical project name. */
    .project-filter { display: block; width: 200px; max-width: 100%; }
    .priority-filter { display: block; width: 160px; max-width: 100%; }

    .reset-filters-btn {
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      color: var(--text-muted);
      border-radius: var(--radius-sm);
      height: 30px;
      width: 30px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
    }
    .reset-filters-btn:hover { color: var(--text-main); border-color: var(--text-muted); }
    .reset-filters-btn .material-symbols-outlined { font-size: 16px; }
  `]
})
export class TaskFilterBarComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  @Input() activePreset: TaskPreset = 'all';
  @Input() searchQuery = '';
  @Input() statusFilterMode: 'active' | 'all' | number = 'active';
  @Input() statuses: TaskStatus[] = [];
  @Input() selectedProjectId: number | null = null;
  @Input() projects: Project[] = [];
  @Input() selectedPriority = '';
  @Input() hasActiveFilters = false;

  @Output() activePresetChange = new EventEmitter<TaskPreset>();
  @Output() searchQueryChange = new EventEmitter<string>();
  @Output() searchApply = new EventEmitter<void>();
  @Output() searchClear = new EventEmitter<void>();
  @Output() statusFilterModeChange = new EventEmitter<'active' | 'all' | number>();
  @Output() selectedProjectIdChange = new EventEmitter<number | null>();
  @Output() selectedPriorityChange = new EventEmitter<string>();
  @Output() resetFilters = new EventEmitter<void>();

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
    return this.statusMemo([this.statuses, this.optionText.currentLang()], () => [
      { value: 'active', label: this.optionText.translate('iam.aktivnye'), title: this.optionText.translate('tasks.tolko_aktivnye_zadachi_bez_vypolnennyh_i_otmenen') },
      { value: 'all', label: this.optionText.translate('common.all'), title: this.optionText.translate('tasks.vse_zadachi_vklyuchaya_zavershennye') },
      ...this.statuses.map(status => ({ value: status.id, label: status.name, color: status.color || undefined })),
    ]);
  }

  /** Priorities, most urgent first; no choice means every priority. */
  priorityOptions(): SMTSelectOption<string>[] {
    return this.priorityMemo([this.optionText.currentLang()], () => [
      { id: 'critical', label: this.optionText.translate('tasks.kriticheskiy') },
      { id: 'high', label: this.optionText.translate('task.priority.high') },
      { id: 'medium', label: this.optionText.translate('tasks.sredniy') },
      { id: 'low', label: this.optionText.translate('task.priority.low') },
    ]);
  }
}
