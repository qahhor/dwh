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
  styleUrl: './user-filter-bar.component.css',
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
