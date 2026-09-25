import { ChangeDetectionStrategy, Component, input, output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { ModuleFilterTab } from '../modules.models';
import { SMTTabBarComponent, SMTTabItem } from '../../../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../../core/services/i18n.service';

@Component({
  selector: 'app-modules-toolbar',
  standalone: true,
  imports: [SMTTabBarComponent, CommonModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <div class="search-box">
        <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
        <input
          type="search"
          class="form-input search-input"
          [placeholder]="'modules.search_placeholder' | t"
          [value]="searchQuery()"
          (input)="onSearchInput($event)"
          [attr.aria-label]="'modules.search_placeholder' | t"
        />
        <button
          *ngIf="searchQuery()"
          type="button"
          class="search-clear-btn"
          [attr.aria-label]="'search.clear_query' | t"
          (click)="clearSearch.emit()"
        >
          <span class="material-symbols-outlined" aria-hidden="true">cancel</span>
        </button>
      </div>

      <smt-tab-bar
        class="status-tab-bar"
        [tabs]="filterTabs()"
        [value]="filterTab()"
        [smtAriaLabel]="'modules.filter_tabs' | t"
        (valueChange)="$event && filterTabChange.emit($event)" />
    </div>
  `,
  styles: [`
    :host { display: block; }
    .toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .search-box {
      position: relative;
      flex: 1;
      max-width: 320px;
      min-width: 200px;
    }
    .search-icon {
      position: absolute;
      left: 10px;
      top: 50%;
      transform: translateY(-50%);
      font-size: 18px;
      color: var(--text-muted);
      pointer-events: none;
    }
    .search-input {
      width: 100%;
      padding-left: 34px;
      padding-right: 32px;
      height: 36px;
      font-size: 13px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background: var(--bg-surface);
      color: var(--text-main);
      box-sizing: border-box;
    }
    .search-clear-btn {
      position: absolute;
      right: 8px;
      top: 50%;
      transform: translateY(-50%);
      background: none;
      border: none;
      padding: 0;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
    }
    .search-clear-btn .material-symbols-outlined {
      font-size: 16px;
    }
  `]
})
export class ModulesToolbarComponent {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  readonly searchQuery = input.required<string>();
  readonly filterTab = input.required<ModuleFilterTab>();
  readonly totalCount = input.required<number>();
  readonly activeCount = input.required<number>();
  readonly systemCount = input.required<number>();
  readonly customCount = input.required<number>();

  readonly searchChange = output<string>();
  readonly clearSearch = output<void>();
  readonly filterTabChange = output<ModuleFilterTab>();

  private readonly tabsMemo = optionsMemo<SMTTabItem<ModuleFilterTab>[]>();

  onSearchInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.searchChange.emit(input.value);
  }

  filterTabs(): SMTTabItem<ModuleFilterTab>[] {
    return this.tabsMemo([this.tabText.currentLang(), this.totalCount(), this.activeCount(), this.systemCount(), this.customCount()], () => [
      { value: 'all', label: this.tabText.translate('modules.tab.all'), count: this.totalCount() },
      { value: 'active', label: this.tabText.translate('modules.tab.active'), count: this.activeCount() },
      { value: 'system', label: this.tabText.translate('modules.tab.system'), count: this.systemCount() },
      { value: 'custom', label: this.tabText.translate('modules.tab.custom'), count: this.customCount() },
    ] as SMTTabItem<ModuleFilterTab>[]);
  }
}
