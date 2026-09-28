import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { Role } from '../../../../core/models/rbac.models';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { GroupedForm, ModuleGroup } from '../roles.models';
import { SMTAlertComponent } from '../../../../shared/ui-kit/components/alert';
import {
  optionsMemo,
  SMTRadioGroupComponent,
  SMTRadioOption,
} from '../../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../../core/services/i18n.service';
import { SMTCheckboxComponent } from '../../../../shared/ui-kit/components/forms/checkbox';
import { SMTInputComponent } from '../../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-role-permissions-matrix',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTCheckboxComponent,
    SMTInputComponent,
    SMTRadioGroupComponent,
    SMTAlertComponent,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
  ],
  template: `
    <!-- Role Meta Header & Save Button -->
    <div class="matrix-header-bar">
      <div class="role-summary-box">
        <div class="role-name-row">
          <h2 class="role-name-text">{{ role().name }}</h2>
          @if (role().pcode) {
            <span class="role-code-badge font-mono">{{ 'iam.system_code' | t: { code: role().pcode ?? '' } }}</span>
          }
          <span class="status-pill" [class.active]="role().state === 'A'">
            {{ (role().state === 'A' ? 'common.active_feminine' : 'common.disabled_feminine') | t }}
          </span>
        </div>

        @if (!isLoading() && !permissionsError()) {
          <div class="role-meter-row">
            <span class="meter-text">
              {{ 'iam.permissions_ratio' | t: { active: activePermissionsCount(), total: totalActionsCount() } }}
              ({{ permissionPercentage() }}%)
            </span>
            <div
              class="meter-track"
              role="progressbar"
              [attr.aria-label]="'iam.dolya_razreshennyh_deystviy' | t"
              aria-valuemin="0"
              aria-valuemax="100"
              [attr.aria-valuenow]="permissionPercentage()"
            >
              <div class="meter-fill" [style.width.%]="permissionPercentage()"></div>
            </div>
          </div>
        }
      </div>

      <div class="matrix-actions-box">
        @if (canGrant() && isPermissionsDirty()) {
          <button
            smt-button
            type="button"
            smtVariant="secondary"
            smtSize="md"
            smtIcon="undo"
            [disabled]="!canEditPermissions()"
            (click)="resetChanges.emit()"
          >
            {{ 'iam.sbrosit_izmeneniya' | t }}
          </button>
        }
        @if (canGrant()) {
          <button
            smt-button
            type="button"
            smtVariant="primary"
            smtSize="md"
            smtIcon="save"
            [smtLoading]="isSaving()"
            [disabled]="!canEditPermissions()"
            (click)="savePermissions.emit()"
          >
            {{ 'iam.sohranit_prava' | t }}
            @if (isPermissionsDirty()) {
              <span> ({{ dirtyPermissionsCount() }})</span>
            }
          </button>
        }
      </div>
    </div>

    @if (isLoading()) {
      <div class="matrix-load-status" role="status">{{ 'common.loading' | t }}</div>
    }
    @if (permissionsError()) {
      <smt-alert smtTone="danger">
        <span>{{ permissionsError() }}</span>
        <button smt-button type="button" smtVariant="secondary" smtSize="sm" (click)="refreshRole.emit(role())">
          {{ 'common.refresh' | t }}
        </button>
      </smt-alert>
    }

    <!-- Superadmin Shield Banner -->
    @if (role().pcode === 'admin') {
      <div class="admin-notice">
        <span class="material-symbols-outlined icon" aria-hidden="true">verified_user</span>
        <span>{{ 'iam.rol_superadministratora_obladaet_absolyutnymi_pr' | t }}</span>
      </div>
    }

    <!-- Auditor Read-Only Banner -->
    @if (role().pcode === 'auditor') {
      <div class="admin-notice auditor-notice">
        <span class="material-symbols-outlined icon" aria-hidden="true">visibility</span>
        <span>{{ 'iam.rol_auditora_prednaznachena_dlya_proveryayuschih' | t }}</span>
      </div>
    }

    <!-- Filter & Search Toolbar -->
    <div class="matrix-toolbar-box">
      <div class="search-and-expand-row">
        <div class="matrix-search-field">
          <label class="sr-only" for="permission-search">{{ 'iam.poisk_po_matrice_prav' | t }}</label>
          <smt-input
            smtFieldId="permission-search"
            name="permissionSearch"
            type="search"
            smtIcon="search"
            clearable
            smtSize="sm"
            class="matrix-search-input"
            [placeholder]="'iam.poisk_po_nazvaniyu_formy_deystviyu_ili_kodu' | t"
            [value]="matrixSearchQuery()"
            (valueChange)="matrixSearchQueryChange.emit($event === null ? '' : '' + $event)"
          />
          @if (matrixSearchQuery().trim()) {
            <span class="search-match-badge">
              {{ 'iam.naydeno_form' | t: { count: matchingFormsCount() } }}
            </span>
          }
        </div>

        <div class="expand-all-links">
          <button type="button" class="text-link" (click)="setAllModulesExpanded.emit(true)">
            {{ 'iam.razvernut_vse' | t }}
          </button>
          <span class="link-sep">•</span>
          <button type="button" class="text-link" (click)="setAllModulesExpanded.emit(false)">
            {{ 'iam.svernut_vse' | t }}
          </button>
          @if (canEditPermissions()) {
            <span class="link-sep">•</span>
            <button type="button" class="text-link" (click)="toggleAllPermissions.emit(true)">
              {{ 'iam.vybrat_vse_prava' | t }}
            </button>
            <span class="link-sep">•</span>
            <button type="button" class="text-link" (click)="toggleReadOnlyAllPermissions.emit()">
              {{ 'iam.tolko_chtenie_vse' | t }}
            </button>
            <span class="link-sep">•</span>
            <button type="button" class="text-link" (click)="toggleAllPermissions.emit(false)">
              {{ 'iam.snyat_vse_prava' | t }}
            </button>
          }
        </div>
      </div>

      <!-- Module Pill Selector -->
      <smt-radio-group
        smtAppearance="chips"
        class="module-filter"
        [options]="moduleOptions()"
        [value]="selectedModuleTab()"
        [smtAriaLabel]="'iam.filtr_moduley_matricy_prav' | t"
        (valueChange)="selectedModuleTabChange.emit($event ?? selectedModuleTab())"
      />
    </div>

    <!-- Modules List -->
    <div class="modules-stack">
      @for (mod of visibleModuleGroups(); track mod) {
        <div class="mod-section-card">
          <!-- Module Section Header -->
          <div class="mod-section-header">
            <button
              type="button"
              class="mod-header-left mod-toggle-btn"
              [attr.aria-expanded]="mod.isExpanded"
              [attr.aria-controls]="'role-module-' + mod.moduleCode"
              (click)="toggleModuleExpand.emit(mod)"
            >
              <span class="material-symbols-outlined chevron-icon" aria-hidden="true">
                {{ mod.isExpanded ? 'expand_more' : 'chevron_right' }}
              </span>
              <span class="material-symbols-outlined mod-icon" aria-hidden="true">{{
                getModuleIcon()(mod.moduleCode)
              }}</span>
              <h3 class="mod-title">{{ mod.moduleName }}</h3>
              <span class="mod-count">{{ 'iam.forms_count' | t: { count: mod.forms.length } }}</span>
            </button>

            <div class="mod-header-right">
              <button
                type="button"
                class="batch-btn"
                [disabled]="!canEditPermissions()"
                (click)="toggleReadOnlyModule.emit(mod)"
              >
                {{ 'iam.tolko_chtenie' | t }}
              </button>
              <span class="batch-divider">|</span>
              <button
                type="button"
                class="batch-btn"
                [disabled]="!canEditPermissions()"
                (click)="toggleAllModule.emit({ mod: mod, select: true })"
              >
                {{ 'iam.vybrat_vse' | t }}
              </button>
              <span class="batch-divider">|</span>
              <button
                type="button"
                class="batch-btn"
                [disabled]="!canEditPermissions()"
                (click)="toggleAllModule.emit({ mod: mod, select: false })"
              >
                {{ 'iam.snyat_vse' | t }}
              </button>
            </div>
          </div>

          <!-- Forms Table -->
          @if (mod.isExpanded) {
            <div
              class="mod-section-body"
              [id]="'role-module-' + mod.moduleCode"
              role="region"
              [attr.aria-label]="'iam.module_permissions_named' | t: { name: mod.moduleName }"
              tabindex="0"
            >
              <table
                class="forms-grid-table"
                [attr.aria-label]="'iam.module_permissions_named' | t: { name: mod.moduleName }"
              >
                <tbody>
                  @for (f of mod.forms; track f) {
                    <tr class="form-grid-row">
                      <th scope="row" class="form-title-col">
                        <div class="form-title-wrap">
                          <span class="form-name-text">{{ f.formName }}</span>
                          <span class="form-code-text font-mono">{{ f.formCode }}</span>
                          @if (role().pcode !== 'admin') {
                            <div class="form-quick-toggles">
                              <button
                                type="button"
                                class="mini-toggle-btn"
                                [disabled]="!canEditPermissions()"
                                (click)="toggleAllForm.emit({ form: f, select: true })"
                              >
                                {{ 'iam.vse' | t }}
                              </button>
                              <span class="dot">•</span>
                              <button
                                type="button"
                                class="mini-toggle-btn"
                                [disabled]="!canEditPermissions()"
                                (click)="toggleAllForm.emit({ form: f, select: false })"
                              >
                                {{ 'iam.snyat' | t }}
                              </button>
                            </div>
                          }
                        </div>
                      </th>

                      <td class="form-actions-col">
                        <div class="actions-chips-wrap">
                          @for (act of f.actions; track act) {
                            <div
                              smt-checkbox
                              class="action-checkbox-card"
                              [class.checked]="hasPermission()(f.formCode, act.action)"
                              [class.dirty]="isPermissionDirty()(f.formCode, act.action)"
                              [class.readonly]="!canEditPermissions()"
                              [title]="f.formCode + '.' + act.action"
                              [checked]="hasPermission()(f.formCode, act.action)"
                              [disabled]="!canEditPermissions()"
                              (smtCheckedChange)="
                                togglePermission.emit({ formCode: f.formCode, action: act.action, checked: $event })
                              "
                            >
                              <span class="chk-label">{{ act.actionName }}</span>
                              @if (isPermissionDirty()(f.formCode, act.action)) {
                                <span class="dirty-indicator-dot" [title]="'iam.izmeneno' | t" aria-hidden="true"
                                  >•</span
                                >
                              }
                            </div>
                          }
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      }

      @if (visibleModuleGroups().length === 0) {
        <div class="no-forms-box">
          <span class="material-symbols-outlined icon" aria-hidden="true">search_off</span>
          <p>{{ 'iam.forms_not_found_for' | t: { query: matrixSearchQuery() } }}</p>
        </div>
      }
    </div>
  `,
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
