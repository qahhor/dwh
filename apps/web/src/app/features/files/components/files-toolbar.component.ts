import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { TranslatePipe } from '@core/services/i18n.service';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { I18nService } from '@core/services/i18n.service';

@Component({
  selector: 'app-files-toolbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTRadioGroupComponent, SMTInputComponent, TranslatePipe],
  template: `
    <div class="filter-toolbar">
      <div class="toolbar-left">
        <!-- Scope Tabs -->
        <smt-radio-group
          smtAppearance="segmented"
          class="scope-filter"
          [options]="scopeOptions()"
          [value]="scope()"
          [smtAriaLabel]="'files.oblast_faylov' | t"
          (valueChange)="scopeChange.emit($event ?? scope())"
        />

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
            [value]="searchQuery()"
            (edited)="searchQueryChange.emit($event === null ? '' : '' + $event)"
            (keyup.enter)="search.emit()"
            (cleared)="clear.emit()"
          />
        </div>
      </div>

      <div class="toolbar-right">
        <button
          type="button"
          class="icon-refresh-btn"
          [attr.aria-label]="'files.obnovit_spisok_faylov' | t"
          (click)="refresh.emit()"
          [title]="'announcements.obnovit_spisok' | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
        </button>
      </div>
    </div>
  `,
  styleUrl: './files-toolbar.component.css',
})
export class FilesToolbarComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  readonly scope = input<'all' | 'mine'>('all');
  readonly searchQuery = input('');

  readonly scopeChange = output<'all' | 'mine'>();
  readonly searchQueryChange = output<string>();
  readonly search = output<void>();
  readonly clear = output<void>();
  readonly refresh = output<void>();

  private readonly scopeMemo = optionsMemo<SMTRadioOption<'all' | 'mine'>[]>();

  scopeOptions(): SMTRadioOption<'all' | 'mine'>[] {
    return this.scopeMemo([this.optionText.currentLang()], () => [
      { value: 'all', label: this.optionText.translate('files.vse_fayly_kompanii'), icon: 'folder_shared' },
      { value: 'mine', label: this.optionText.translate('files.moi_fayly'), icon: 'person' },
    ]);
  }
}
