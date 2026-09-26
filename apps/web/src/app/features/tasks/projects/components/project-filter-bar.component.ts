import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { ProjectStateFilter } from '../projects.models';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '../../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../../core/services/i18n.service';
import { SMTInputComponent, SMTInputValue } from '../../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-project-filter-bar',
  standalone: true,
  imports: [
    SMTRadioGroupComponent, SMTInputComponent, CommonModule,
    TranslatePipe
  ],
  template: `
    <div class="toolbar">
      <label class="sr-only" for="project-search">{{ 'projects.poisk_proektov' | t }}</label>
      <smt-input
        class="search-field"
        smtFieldId="project-search"
        name="projectSearch"
        type="search"
        smtIcon="search"
        clearable
        [placeholder]="'projects.poisk_po_nazvaniyu_ili_opisaniyu' | t"
        [value]="searchQuery"
        (valueChange)="onSearchValue($event)" />

      <smt-radio-group
        smtAppearance="segmented"
        class="status-filter"
        [options]="stateOptions()"
        [value]="selectedState"
        [smtAriaLabel]="'projects.filtr_proektov_po_statusu' | t"
        (valueChange)="stateChange.emit($event ?? selectedState)" />
    </div>
  `,
  styles: [`
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
    }
    .search-field {
      min-width: 260px;
      flex: 1;
      max-width: 400px;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      border-width: 0;
    }
  `]
})
export class ProjectFilterBarComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  @Input() searchQuery = '';
  @Input() selectedState: ProjectStateFilter = 'all';
  @Output() searchChange = new EventEmitter<string>();
  @Output() clearSearch = new EventEmitter<void>();
  @Output() stateChange = new EventEmitter<ProjectStateFilter>();

  private readonly stateMemo = optionsMemo<SMTRadioOption<ProjectStateFilter>[]>();

  /** Every edit filters the list; emptying the field (its clear button) clears the search. */
  onSearchValue(value: SMTInputValue): void {
    const query = value === null ? '' : String(value);
    if (query === '') {
      this.clearSearch.emit();
    } else {
      this.searchChange.emit(query);
    }
  }

  stateOptions(): SMTRadioOption<ProjectStateFilter>[] {
    return this.stateMemo([this.optionText.currentLang()], () => [
      { value: 'all', label: this.optionText.translate('common.all') },
      { value: 'A', label: this.optionText.translate('iam.aktivnye'), color: 'var(--success)' },
      { value: 'P', label: this.optionText.translate('projects.arhiv'), color: 'var(--text-light)' },
    ]);
  }
}
