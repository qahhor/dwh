import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { ProjectStateFilter } from '../projects.models';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '../../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../../core/services/i18n.service';

@Component({
  selector: 'app-project-filter-bar',
  standalone: true,
  imports: [
    SMTRadioGroupComponent, CommonModule,
    FormsModule,
    TranslatePipe
  ],
  template: `
    <div class="toolbar">
      <div class="search-field">
        <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
        <label class="sr-only" for="project-search">{{ 'projects.poisk_proektov' | t }}</label>
        <input
          id="project-search"
          name="projectSearch"
          type="text"
          class="search-input"
          [placeholder]="'projects.poisk_po_nazvaniyu_ili_opisaniyu' | t"
          [ngModel]="searchQuery"
          (ngModelChange)="searchChange.emit($event)"
        />
        <button *ngIf="searchQuery" type="button" class="btn-icon project-search-clear" style="position: absolute; right: 6px;" [attr.aria-label]="'projects.ochistit_poisk_proektov' | t" (click)="clearSearch.emit()">
          <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">close</span>
        </button>
      </div>

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
      position: relative;
      display: flex;
      align-items: center;
      min-width: 260px;
      flex: 1;
      max-width: 400px;
    }
    .search-icon {
      position: absolute;
      left: 10px;
      color: var(--text-muted);
      font-size: 18px;
      pointer-events: none;
    }
    .search-input {
      width: 100%;
      height: 34px;
      padding: 6px 32px 6px 34px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-hover);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: all 0.15s ease;
    }
    .search-input:focus {
      border-color: var(--primary);
      background-color: var(--bg-surface);
    }
    .btn-icon {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 4px;
      border-radius: 4px;
    }
    .btn-icon:hover { color: var(--text-main); background-color: var(--bg-hover); }
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

  stateOptions(): SMTRadioOption<ProjectStateFilter>[] {
    return this.stateMemo([this.optionText.currentLang()], () => [
      { value: 'all', label: this.optionText.translate('common.all') },
      { value: 'A', label: this.optionText.translate('iam.aktivnye'), color: 'var(--success)' },
      { value: 'P', label: this.optionText.translate('projects.arhiv'), color: 'var(--text-light)' },
    ]);
  }
}
