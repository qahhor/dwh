import { ChangeDetectionStrategy, Component, input, output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { SMTTabBarComponent, SMTTabItem } from '../../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../core/services/i18n.service';

@Component({
  selector: 'app-announcements-toolbar',
  standalone: true,
  imports: [SMTTabBarComponent, CommonModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <smt-tab-bar
        class="status-tab-bar"
        [tabs]="statusTabs()"
        [value]="statusFilter()"
        [smtAriaLabel]="'announcements.vse_statusy' | t"
        (valueChange)="$event && filterChange.emit($event)" />

      <div class="search-box">
        <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
        <input
          type="search"
          class="search-input"
          [placeholder]="'announcements.poisk' | t"
          [attr.aria-label]="'announcements.poisk' | t"
          [value]="searchQuery()"
          (input)="onInput($event)"
        />
        <button
          *ngIf="searchQuery().length > 0"
          type="button"
          class="clear-search-btn"
          (click)="searchClear.emit()"
          [attr.aria-label]="'announcements.sbrosit_filtry' | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </div>
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
      position: relative;
      display: flex;
      align-items: center;
      min-width: 240px;
    }

    .search-icon {
      position: absolute;
      left: 10px;
      font-size: 18px;
      color: var(--text-muted);
      pointer-events: none;
    }

    .search-input {
      width: 100%;
      height: 34px;
      padding: 6px 30px 6px 32px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      background: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      box-sizing: border-box;
      transition: border-color 0.15s ease;
    }

    .search-input:focus {
      outline: none;
      border-color: var(--primary);
    }

    .clear-search-btn {
      position: absolute;
      right: 6px;
      width: 22px;
      height: 22px;
      display: flex;
      align-items: center;
      justify-content: center;
      border: 0;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      border-radius: 50%;
    }

    .clear-search-btn:hover {
      background: var(--bg-hover);
      color: var(--text-main);
    }

    .clear-search-btn .material-symbols-outlined {
      font-size: 16px;
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

  onInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.searchChange.emit(input.value);
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
