import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { Role } from '@core/models/rbac.models';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { GroupedForm, ModuleGroup } from '../roles.models';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { I18nService } from '@core/services/i18n.service';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-role-permissions-matrix',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTCheckboxComponent,
    SMTInputComponent,
    SMTRadioGroupComponent,
    SMTAlertComponent,
    TranslatePipe,
    SMTButtonComponent,
  ],
  templateUrl: './role-permissions-matrix.component.html',
  styleUrl: './role-permissions-matrix.component.css',
})
export class RolePermissionsMatrixComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  readonly hasPermission = input.required<(formCode: string, action: string) => boolean>();
  readonly isPermissionDirty = input.required<(formCode: string, action: string) => boolean>();
  readonly getModuleIcon = input.required<(moduleCode: string) => string>();
  readonly getModuleActionsCount = input.required<(mod: ModuleGroup) => number>();

  readonly role = input.required<Role>();

  readonly isLoading = input(false);
  readonly isSaving = input(false);
  readonly activePermissionsCount = input(0);
  readonly totalActionsCount = input(0);
  readonly permissionPercentage = input(0);
  readonly isPermissionsDirty = input(false);
  readonly dirtyPermissionsCount = input(0);
  readonly canGrant = input(false);
  readonly canEditPermissions = input(false);
  readonly matrixSearchQuery = input('');
  readonly matchingFormsCount = input(0);
  readonly formsCount = input(0);
  readonly selectedModuleTab = input('all');
  readonly moduleGroups = input<ModuleGroup[]>([]);
  readonly visibleModuleGroups = input<ModuleGroup[]>([]);
  readonly permissionsError = input('');

  readonly resetChanges = output<void>();
  readonly savePermissions = output<void>();
  readonly refreshRole = output<Role>();
  readonly matrixSearchQueryChange = output<string>();
  readonly selectedModuleTabChange = output<string>();
  readonly setAllModulesExpanded = output<boolean>();
  readonly toggleAllPermissions = output<boolean>();
  readonly toggleReadOnlyAllPermissions = output<void>();
  readonly toggleModuleExpand = output<ModuleGroup>();
  readonly toggleReadOnlyModule = output<ModuleGroup>();
  readonly toggleAllModule = output<{
    mod: ModuleGroup;
    select: boolean;
  }>();
  readonly toggleAllForm = output<{
    form: GroupedForm;
    select: boolean;
  }>();
  readonly togglePermission = output<{
    formCode: string;
    action: string;
    checked: boolean;
  }>();

  private readonly moduleMemo = optionsMemo<SMTRadioOption<string>[]>();

  /** "All sections" and each module as chips with its number of actions. */
  moduleOptions(): SMTRadioOption<string>[] {
    return this.moduleMemo([this.formsCount(), this.moduleGroups(), this.optionText.currentLang()], () => [
      { value: 'all', label: this.optionText.translate('iam.all_sections_count', { count: this.formsCount() }) },
      ...this.moduleGroups().map((mod) => ({
        value: mod.moduleCode,
        label: mod.moduleName,
        count: this.getModuleActionsCount()(mod),
      })),
    ]);
  }
}
