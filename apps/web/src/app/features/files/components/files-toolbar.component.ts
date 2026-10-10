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
          [smtAriaLabel]="'files.list.scope' | t"
          (valueChange)="scopeChange.emit($event ?? scope())"
        />

        <!-- Search Input -->
        <div class="search-box">
          <smt-input
            class="search-field"
            smtFieldId="file-search"
            [smtAriaLabel]="'files.list.search_files' | t"
            name="fileSearch"
            type="search"
            smtIcon="search"
            clearable
            [placeholder]="'files.list.search_files_by_name' | t"
            [value]="searchQuery()"
            (edited)="searchQueryChange.emit($event === null ? '' : '' + $event)"
            (keyup.enter)="searchSubmit.emit()"
            (cleared)="clear.emit()"
          />
        </div>
      </div>

      <div class="toolbar-right">
        <button
          type="button"
          class="icon-refresh-btn"
          [attr.aria-label]="'files.list.refresh_files' | t"
          (click)="refresh.emit()"
          [title]="'announcements.list.refresh' | t"
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
  readonly searchSubmit = output<void>();
  readonly clear = output<void>();
  readonly refresh = output<void>();

  private readonly scopeMemo = optionsMemo<SMTRadioOption<'all' | 'mine'>[]>();

  scopeOptions(): SMTRadioOption<'all' | 'mine'>[] {
    return this.scopeMemo([this.optionText.currentLang()], () => [
      { value: 'all', label: this.optionText.translate('files.list.all_company_files'), icon: 'folder_shared' },
      { value: 'mine', label: this.optionText.translate('files.list.my_files'), icon: 'person' },
    ]);
  }
}
