import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { Role } from '@core/models/rbac.models';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { I18nService } from '@core/services/i18n.service';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-user-filter-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTRadioGroupComponent,
    SMTSelectComponent,
    SMTInputComponent,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
  ],
  templateUrl: './user-filter-bar.component.html',
  styles: [
    `
      .filter-trigger-btn:focus-visible,
      .clear-pill-btn:focus-visible,
      .reset-all-filters-btn:focus-visible {
        outline: 2px solid var(--primary);
        outline-offset: 2px;
        border-radius: var(--radius-sm);
      }
      .toolbar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        flex-wrap: wrap;
      }
      .search-field {
        position: relative;
        display: flex;
        align-items: center;
        width: 320px;
        max-width: 100%;
      }
      .toolbar-controls {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .filter-popover-wrapper {
        position: relative;
      }
      .filter-trigger-btn {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        height: 32px;
        padding: 0 10px;
        border-radius: var(--radius-sm);
        border: 1px solid var(--border-color);
        background-color: var(--bg-surface);
        color: var(--text-muted);
        font-size: 12px;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .filter-trigger-btn .icon {
        font-size: 16px;
      }
      .filter-trigger-btn:hover,
      .filter-trigger-btn.open {
        color: var(--text-main);
        border-color: var(--text-muted);
      }
      .filter-trigger-btn.has-filters {
        color: var(--primary);
        border-color: var(--primary);
      }
      .filter-dot {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background-color: var(--primary);
      }

      .active-filters-bar {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 8px;
        padding: 6px 12px;
        background-color: var(--bg-hover);
        border-radius: var(--radius-sm);
        border: 1px solid var(--border-color);
      }
      .active-filters-label {
        font-size: 11px;
        font-weight: 600;
        color: var(--text-muted);
        text-transform: uppercase;
        letter-spacing: 0.3px;
      }
      .filter-pill {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 11px;
        font-weight: 500;
        color: var(--text-main);
        background: var(--bg-surface);
        padding: 3px 8px;
        border-radius: 9999px;
        border: 1px solid var(--border-color);
      }
      .clear-pill-btn {
        background: none;
        border: none;
        padding: 0;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        color: var(--text-muted);
        cursor: pointer;
        border-radius: 50%;
        width: 16px;
        height: 16px;
        transition:
          color 0.15s ease,
          background-color 0.15s ease;
      }
      .clear-pill-btn .material-symbols-outlined {
        font-size: 13px;
      }
      .clear-pill-btn:hover {
        color: var(--danger);
        background-color: rgba(239, 68, 68, 0.1);
      }
      .reset-all-filters-btn {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 11px;
        font-weight: 500;
        color: var(--text-muted);
        background: none;
        border: none;
        cursor: pointer;
        padding: 3px 8px;
        border-radius: var(--radius-sm);
        transition:
          color 0.15s ease,
          background-color 0.15s ease;
      }
      .reset-all-filters-btn .material-symbols-outlined {
        font-size: 15px;
      }
      .reset-all-filters-btn:hover {
        color: var(--danger);
        background-color: rgba(239, 68, 68, 0.08);
      }

      .filter-dropdown {
        position: absolute;
        top: calc(100% + 4px);
        right: 0;
        width: 260px;
        background-color: var(--bg-surface);
        border: 1px solid var(--border-color);
        border-radius: var(--radius-md);
        box-shadow: 0 6px 16px rgba(0, 0, 0, 0.1);
        padding: 12px;
        z-index: 100;
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .filter-dropdown-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        border-bottom: 1px solid var(--border-color);
        padding-bottom: 6px;
      }
      .dropdown-title {
        font-size: 12px;
        font-weight: 600;
        color: var(--text-main);
      }
      .reset-link {
        background: transparent;
        border: none;
        font-size: 11px;
        color: var(--primary);
        cursor: pointer;
        padding: 0;
      }
      .filter-dropdown-body {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .filter-group {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .filter-caption {
        font-size: 11px;
        color: var(--text-muted);
      }

      /* Accessibility focus indicators */

      @media (max-width: 640px) {
        .toolbar-controls {
          width: 100%;
          flex-wrap: wrap;
          min-width: 0;
        }
      }
    `,
  ],
})
export class UserFilterBarComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  readonly searchQuery = input('');
  readonly isFilterMenuOpen = input(false);
  readonly hasExtraFilters = input(false);
  readonly roles = input<Role[]>([]);
  readonly selectedRoleId = input<number | null>(null);
  readonly isLoading = input(false);
  readonly hasAnyActiveFilters = input(false);
  readonly selectedRoleName = input('');

  readonly selectedState = input('');
  readonly selected2fa = input<boolean | null>(null);

  readonly searchQueryChange = output<string>();
  readonly searchInput = output<void>();
  readonly clearSearch = output<void>();
  readonly stateFilterChange = output<string>();
  readonly toggleFilterMenu = output<MouseEvent>();
  readonly resetExtraFilters = output<void>();
  readonly roleFilterChange = output<number | null>();
  readonly twoFactorFilterChange = output<boolean | null>();
  readonly refresh = output<void>();
  readonly clearStateFilter = output<void>();
  readonly clearRoleFilter = output<void>();
  readonly clear2faFilter = output<void>();
  readonly resetAllFilters = output<void>();

  private readonly stateMemo = optionsMemo<SMTRadioOption<string>[]>();
  private readonly roleMemo = optionsMemo<SMTSelectOption<number>[]>();
  private readonly twoFactorMemo = optionsMemo<SMTSelectOption<boolean>[]>();

  stateOptions(): SMTRadioOption<string>[] {
    return this.stateMemo([this.optionText.currentLang()], () => [
      { value: '', label: this.optionText.translate('common.all') },
      { value: 'A', label: this.optionText.translate('iam.aktivnye'), color: 'var(--success)' },
      { value: 'P', label: this.optionText.translate('iam.zablokirovannye'), color: 'var(--danger)' },
    ]);
  }

  roleOptions(): SMTSelectOption<number>[] {
    return this.roleMemo([this.roles()], () => this.roles().map((role) => ({ id: role.id, label: role.name })));
  }

  twoFactorOptions(): SMTSelectOption<boolean>[] {
    return this.twoFactorMemo([this.optionText.currentLang()], () => [
      { id: true, label: this.optionText.translate('iam.tolko_s_2fa') },
      { id: false, label: this.optionText.translate('iam.bez_2fa') },
    ]);
  }
}
