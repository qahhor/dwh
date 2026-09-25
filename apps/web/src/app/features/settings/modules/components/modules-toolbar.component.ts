import { ChangeDetectionStrategy, Component, input, output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { ModuleFilterTab } from '../modules.models';
import { SMTTabBarComponent, SMTTabItem } from '../../../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../../core/services/i18n.service';
import { SMTInputComponent } from '../../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-modules-toolbar',
  standalone: true,
  imports: [SMTInputComponent, SMTTabBarComponent, CommonModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <smt-input
        class="search-box"
        type="search"
        smtIcon="search"
        clearable
        [placeholder]="'modules.search_placeholder' | t"
        [smtAriaLabel]="'modules.search_placeholder' | t"
        [value]="searchQuery()"
        (valueChange)="searchChange.emit($event === null ? '' : '' + $event)" />

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
  readonly filterTabChange = output<ModuleFilterTab>();

  private readonly tabsMemo = optionsMemo<SMTTabItem<ModuleFilterTab>[]>();

  filterTabs(): SMTTabItem<ModuleFilterTab>[] {
    return this.tabsMemo([this.tabText.currentLang(), this.totalCount(), this.activeCount(), this.systemCount(), this.customCount()], () => [
      { value: 'all', label: this.tabText.translate('modules.tab.all'), count: this.totalCount() },
      { value: 'active', label: this.tabText.translate('modules.tab.active'), count: this.activeCount() },
      { value: 'system', label: this.tabText.translate('modules.tab.system'), count: this.systemCount() },
      { value: 'custom', label: this.tabText.translate('modules.tab.custom'), count: this.customCount() },
    ] as SMTTabItem<ModuleFilterTab>[]);
  }
}
