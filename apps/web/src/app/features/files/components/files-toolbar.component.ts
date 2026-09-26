import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../core/services/i18n.service';

@Component({
  selector: 'app-files-toolbar',
  standalone: true,
  imports: [
    SMTRadioGroupComponent, SMTInputComponent, SMTInputValueAccessor, CommonModule,
    FormsModule,
    TranslatePipe
  ],
  template: `
    <div class="filter-toolbar">
      <div class="toolbar-left">
        <!-- Scope Tabs -->
        <smt-radio-group
          smtAppearance="segmented"
          class="scope-filter"
          [options]="scopeOptions()"
          [value]="scope"
          [smtAriaLabel]="'files.oblast_faylov' | t"
          (valueChange)="scopeChange.emit($event ?? scope)" />

        <!-- Search Input -->
        <div class="search-box">
          <label class="sr-only" for="file-search">{{ 'files.poisk_faylov' | t }}</label>
          <smt-input
            class="search-field"
            smtFieldId="file-search"
            name="fileSearch"
            type="search"
            smtIcon="search"
            clearable
            [placeholder]="'files.poisk_faylov_po_imeni' | t"
            [ngModel]="searchQuery"
            (ngModelChange)="searchQueryChange.emit($event)"
            (keyup.enter)="search.emit()"
            (cleared)="clear.emit()" />
        </div>
      </div>

      <div class="toolbar-right">
        <button type="button" class="icon-refresh-btn" [attr.aria-label]="'files.obnovit_spisok_faylov' | t" (click)="refresh.emit()" [title]="'announcements.obnovit_spisok' | t">
          <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
        </button>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
    }

    .filter-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }

    .toolbar-left {
      display: flex;
      align-items: center;
      gap: 12px;
      flex: 1;
      min-width: 0;
      flex-wrap: wrap;
    }

    .toolbar-right {
      display: flex;
      align-items: center;
      gap: 8px;
    }






    .search-box {
      position: relative;
      flex: 1;
      max-width: 380px;
      min-width: 200px;
    }





    .icon-refresh-btn {
      width: 36px;
      height: 36px;
      border-radius: 8px;
      border: 1px solid var(--border-color);
      background: var(--bg-surface);
      color: var(--text-light);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .icon-refresh-btn:hover {
      background: var(--bg-hover);
      color: var(--text-main);
    }

    .icon-refresh-btn .material-symbols-outlined {
      font-size: 18px;
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
      border: 0;
    }
  `]
})
export class FilesToolbarComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  @Input() scope: 'all' | 'mine' = 'all';
  @Input() searchQuery = '';

  @Output() scopeChange = new EventEmitter<'all' | 'mine'>();
  @Output() searchQueryChange = new EventEmitter<string>();
  @Output() search = new EventEmitter<void>();
  @Output() clear = new EventEmitter<void>();
  @Output() refresh = new EventEmitter<void>();

  private readonly scopeMemo = optionsMemo<SMTRadioOption<'all' | 'mine'>[]>();

  scopeOptions(): SMTRadioOption<'all' | 'mine'>[] {
    return this.scopeMemo([this.optionText.currentLang()], () => [
      { value: 'all', label: this.optionText.translate('files.vse_fayly_kompanii'), icon: 'folder_shared' },
      { value: 'mine', label: this.optionText.translate('files.moi_fayly'), icon: 'person' },
    ]);
  }
}
