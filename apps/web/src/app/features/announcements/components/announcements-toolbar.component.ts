import { ChangeDetectionStrategy, Component, input, output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { SMTTabBarComponent, SMTTabItem } from '../../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../core/services/i18n.service';
import { SMTInputComponent, SMTInputValue } from '../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-announcements-toolbar',
  standalone: true,
  imports: [SMTTabBarComponent, SMTInputComponent, CommonModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <smt-tab-bar
        class="status-tab-bar"
        [tabs]="statusTabs()"
        [value]="statusFilter()"
        [smtAriaLabel]="'announcements.vse_statusy' | t"
        (valueChange)="$event && filterChange.emit($event)" />

      <smt-input
        class="search-box"
        type="search"
        smtIcon="search"
        clearable
        [placeholder]="'announcements.poisk' | t"
        [smtAriaLabel]="'announcements.poisk' | t"
        [value]="searchQuery()"
        (valueChange)="onSearch($event)" />
    </div>
  `,
  styles: [`
    :host { display: block; }
    .toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      padding: 6px 0;
    }







    .search-box {
      width: 240px;
    }

    @media (max-width: 680px) {
      .toolbar { flex-direction: column; align-items: stretch; }
      .search-box { width: 100%; }
    }
  `]
})
export class AnnouncementsToolbarComponent {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  readonly totalCount = input.required<number>();
  readonly publishedCount = input.required<number>();
  readonly draftCount = input.required<number>();
  readonly archivedCount = input.required<number>();
  readonly statusFilter = input.required<'ALL' | 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'>();
  readonly searchQuery = input.required<string>();

  readonly filterChange = output<'ALL' | 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'>();
  readonly searchChange = output<string>();
  readonly searchClear = output<void>();

  private readonly tabsMemo = optionsMemo<SMTTabItem<'ALL' | 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'>[]>();

  /** The typed text; an emptied box (typed away or cleared with its button) clears the search. */
  onSearch(value: SMTInputValue): void {
    const text = value === null ? '' : String(value);
    if (text) {
      this.searchChange.emit(text);
    } else {
      this.searchClear.emit();
    }
  }

  statusTabs(): SMTTabItem<'ALL' | 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'>[] {
    return this.tabsMemo([this.tabText.currentLang(), this.totalCount(), this.publishedCount(), this.draftCount(), this.archivedCount()], () => [
      { value: 'ALL', label: this.tabText.translate('announcements.vse_statusy'), count: this.totalCount() },
      { value: 'PUBLISHED', label: this.tabText.translate('announcements.status_published'), count: this.publishedCount() },
      { value: 'DRAFT', label: this.tabText.translate('announcements.status_draft'), count: this.draftCount() },
      { value: 'ARCHIVED', label: this.tabText.translate('announcements.status_archived'), count: this.archivedCount() },
    ]);
  }
}
