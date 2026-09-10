import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Role } from '../../../../core/models/rbac.models';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { UiButtonComponent } from '../../../../shared/ui/ui-button.component';
import { GroupedForm, ModuleGroup } from '../roles.models';

@Component({
  selector: 'app-role-permissions-matrix',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslatePipe, UiButtonComponent],
  template: `
    <!-- Role Meta Header & Save Button -->
    <div class="matrix-header-bar">
      <div class="role-summary-box">
        <div class="role-name-row">
          <h2 class="role-name-text">{{ role.name }}</h2>
          <span class="role-code-badge font-mono" *ngIf="role.pcode">{{ 'iam.system_code' | t:{code: role.pcode} }}</span>
          <span class="status-pill" [class.active]="role.state === 'A'">
            {{ (role.state === 'A' ? 'common.active_feminine' : 'common.disabled_feminine') | t }}
          </span>
        </div>

        <div class="role-meter-row" *ngIf="!isLoading && !permissionsError">
          <span class="meter-text">
            {{ 'iam.permissions_ratio' | t:{active: activePermissionsCount, total: totalActionsCount} }}
            ({{ permissionPercentage }}%)
          </span>
          <div
            class="meter-track"
            role="progressbar"
            [attr.aria-label]="'iam.dolya_razreshennyh_deystviy' | t"
            aria-valuemin="0"
            aria-valuemax="100"
            [attr.aria-valuenow]="permissionPercentage"
          >
            <div class="meter-fill" [style.width.%]="permissionPercentage"></div>
          </div>
        </div>
      </div>

      <div class="matrix-actions-box">
        <ui-button
          *ngIf="canGrant && isPermissionsDirty"
          variant="secondary"
          size="md"
          icon="undo"
          [disabled]="!canEditPermissions"
          (onClick)="resetChanges.emit()"
        >
          {{ 'iam.sbrosit_izmeneniya' | t }}
        </ui-button>
        <ui-button
          *ngIf="canGrant"
          variant="primary"
          size="md"
          icon="save"
          [loading]="isSaving"
          [disabled]="!canEditPermissions"
          (onClick)="savePermissions.emit()"
        >
          {{ 'iam.sohranit_prava' | t }}<span *ngIf="isPermissionsDirty"> ({{ dirtyPermissionsCount }})</span>
        </ui-button>
      </div>
    </div>

    <div *ngIf="isLoading" class="matrix-load-status" role="status">{{ 'common.loading' | t }}</div>
    <div *ngIf="permissionsError" class="alert alert-error" role="alert">
      <span>{{ permissionsError }}</span>
      <ui-button variant="secondary" size="sm" (onClick)="refreshRole.emit(role)">{{ 'common.refresh' | t }}</ui-button>
    </div>

    <!-- Superadmin Shield Banner -->
    <div *ngIf="role.pcode === 'admin'" class="admin-notice">
      <span class="material-symbols-outlined icon" aria-hidden="true">verified_user</span>
      <span>{{ 'iam.rol_superadministratora_obladaet_absolyutnymi_pr' | t }}</span>
    </div>

    <!-- Auditor Read-Only Banner -->
    <div *ngIf="role.pcode === 'auditor'" class="admin-notice auditor-notice">
      <span class="material-symbols-outlined icon" aria-hidden="true">visibility</span>
      <span>{{ 'iam.rol_auditora_prednaznachena_dlya_proveryayuschih' | t }}</span>
    </div>

    <!-- Filter & Search Toolbar -->
    <div class="matrix-toolbar-box">
      <div class="search-and-expand-row">
        <div class="matrix-search-field">
          <span class="material-symbols-outlined icon" aria-hidden="true">search</span>
          <label class="sr-only" for="permission-search">{{ 'iam.poisk_po_matrice_prav' | t }}</label>
          <input
            id="permission-search"
            name="permissionSearch"
            type="text"
            class="matrix-search-input"
            [placeholder]="'iam.poisk_po_nazvaniyu_formy_deystviyu_ili_kodu' | t"
            [ngModel]="matrixSearchQuery"
            (ngModelChange)="matrixSearchQueryChange.emit($event)"
          />
          <span *ngIf="matrixSearchQuery.trim()" class="search-match-badge">
            {{ 'iam.naydeno_form' | t:{count: matchingFormsCount} }}
          </span>
          <button *ngIf="matrixSearchQuery" type="button" class="clear-search-btn" [attr.aria-label]="'iam.ochistit_poisk_po_matrice_prav' | t" (click)="matrixSearchQueryChange.emit('')">
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>

        <div class="expand-all-links">
          <button type="button" class="text-link" (click)="setAllModulesExpanded.emit(true)">{{ 'iam.razvernut_vse' | t }}</button>
          <span class="link-sep">•</span>
          <button type="button" class="text-link" (click)="setAllModulesExpanded.emit(false)">{{ 'iam.svernut_vse' | t }}</button>
          <ng-container *ngIf="canEditPermissions">
            <span class="link-sep">•</span>
            <button type="button" class="text-link" (click)="toggleAllPermissions.emit(true)">{{ 'iam.vybrat_vse_prava' | t }}</button>
            <span class="link-sep">•</span>
            <button type="button" class="text-link" (click)="toggleReadOnlyAllPermissions.emit()">{{ 'iam.tolko_chtenie_vse' | t }}</button>
            <span class="link-sep">•</span>
            <button type="button" class="text-link" (click)="toggleAllPermissions.emit(false)">{{ 'iam.snyat_vse_prava' | t }}</button>
          </ng-container>
        </div>
      </div>

      <!-- Module Pill Selector -->
      <div class="module-filter-pills" role="group" [attr.aria-label]="'iam.filtr_moduley_matricy_prav' | t">
        <button
          type="button"
          class="mod-pill-btn"
          [class.active]="selectedModuleTab === 'all'"
          [attr.aria-pressed]="selectedModuleTab === 'all'"
          (click)="selectedModuleTabChange.emit('all')"
        >
          {{ 'iam.all_sections_count' | t:{count: formsCount} }}
        </button>
        <button
          *ngFor="let mod of moduleGroups"
          type="button"
          class="mod-pill-btn"
          [class.active]="selectedModuleTab === mod.moduleCode"
          [attr.aria-pressed]="selectedModuleTab === mod.moduleCode"
          (click)="selectedModuleTabChange.emit(mod.moduleCode)"
        >
          {{ mod.moduleName }} ({{ getModuleActionsCount(mod) }})
        </button>
      </div>
    </div>

    <!-- Modules List -->
    <div class="modules-stack">
      <div *ngFor="let mod of visibleModuleGroups" class="mod-section-card">
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
            <span class="material-symbols-outlined mod-icon" aria-hidden="true">{{ getModuleIcon(mod.moduleCode) }}</span>
            <h3 class="mod-title">{{ mod.moduleName }}</h3>
            <span class="mod-count">{{ 'iam.forms_count' | t:{count: mod.forms.length} }}</span>
          </button>

          <div class="mod-header-right">
            <button
              type="button"
              class="batch-btn"
              [disabled]="!canEditPermissions"
              (click)="toggleReadOnlyModule.emit(mod)"
            >
              {{ 'iam.tolko_chtenie' | t }}
            </button>
            <span class="batch-divider">|</span>
            <button
              type="button"
              class="batch-btn"
              [disabled]="!canEditPermissions"
              (click)="toggleAllModule.emit({ mod: mod, select: true })"
            >
              {{ 'iam.vybrat_vse' | t }}
            </button>
            <span class="batch-divider">|</span>
            <button
              type="button"
              class="batch-btn"
              [disabled]="!canEditPermissions"
              (click)="toggleAllModule.emit({ mod: mod, select: false })"
            >
              {{ 'iam.snyat_vse' | t }}
            </button>
          </div>
        </div>

        <!-- Forms Table -->
        <div
          class="mod-section-body"
          *ngIf="mod.isExpanded"
          [id]="'role-module-' + mod.moduleCode"
          role="region"
          [attr.aria-label]="'iam.module_permissions_named' | t:{name: mod.moduleName}"
          tabindex="0"
        >
          <table class="forms-grid-table" [attr.aria-label]="'iam.module_permissions_named' | t:{name: mod.moduleName}">
            <tbody>
              <tr *ngFor="let f of mod.forms" class="form-grid-row">
                <td class="form-title-col">
                  <div class="form-title-wrap">
                    <span class="form-name-text">{{ f.formName }}</span>
                    <span class="form-code-text font-mono">{{ f.formCode }}</span>
                    <div class="form-quick-toggles" *ngIf="role.pcode !== 'admin'">
                      <button type="button" class="mini-toggle-btn" [disabled]="!canEditPermissions" (click)="toggleAllForm.emit({ form: f, select: true })">{{ 'iam.vse' | t }}</button>
                      <span class="dot">•</span>
                      <button type="button" class="mini-toggle-btn" [disabled]="!canEditPermissions" (click)="toggleAllForm.emit({ form: f, select: false })">{{ 'iam.snyat' | t }}</button>
                    </div>
                  </div>
                </td>

                <td class="form-actions-col">
                  <div class="actions-chips-wrap">
                    <label
                      *ngFor="let act of f.actions"
                      class="action-checkbox-card"
                      [class.checked]="hasPermission(f.formCode, act.action)"
                      [class.dirty]="isPermissionDirty(f.formCode, act.action)"
                      [class.readonly]="!canEditPermissions"
                      [title]="f.formCode + '.' + act.action"
                    >
                      <input
                        type="checkbox"
                        class="chk-input"
                        [checked]="hasPermission(f.formCode, act.action)"
                        [disabled]="!canEditPermissions"
                        (change)="togglePermission.emit({ formCode: f.formCode, action: act.action, event: $event })"
                      />
                      <span class="chk-label">{{ act.actionName }}</span>
                      <span *ngIf="isPermissionDirty(f.formCode, act.action)" class="dirty-indicator-dot" [title]="'iam.izmeneno' | t" aria-hidden="true">•</span>
                    </label>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div *ngIf="visibleModuleGroups.length === 0" class="no-forms-box">
        <span class="material-symbols-outlined icon" aria-hidden="true">search_off</span>
        <p>{{ 'iam.forms_not_found_for' | t:{query: matrixSearchQuery} }}</p>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
    }
    .matrix-header-bar {
      position: sticky;
      top: 0;
      z-index: 10;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 18px;
      border-bottom: 1px solid var(--border-color);
      border-top-left-radius: var(--radius-md);
      border-top-right-radius: var(--radius-md);
      background-color: var(--bg-surface);
      gap: 16px;
      flex-wrap: wrap;
      box-shadow: 0 1px 3px rgba(0,0,0,0.05);
    }
    .role-summary-box { display: flex; flex-direction: column; gap: 6px; }
    .role-name-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .role-name-text { font-size: 17px; font-weight: 600; margin: 0; color: var(--text-main); }
    .role-code-badge {
      font-size: 11px;
      color: var(--primary);
      background: rgba(99,102,241,0.1);
      padding: 2px 7px;
      border-radius: 4px;
    }
    .status-pill {
      font-size: 11px;
      color: var(--text-muted);
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      padding: 2px 7px;
      border-radius: 4px;
    }
    .status-pill.active { color: var(--success); border-color: rgba(16,185,129,0.3); }

    .role-meter-row { display: flex; align-items: center; gap: 12px; }
    .meter-text { font-size: 12px; color: var(--text-muted); }
    .meter-track {
      width: 120px;
      height: 6px;
      background-color: var(--border-color);
      border-radius: 3px;
      overflow: hidden;
    }
    .meter-fill {
      height: 100%;
      background-color: var(--primary);
      transition: width 0.2s ease;
    }

    .matrix-actions-box { display: flex; align-items: center; gap: 8px; }

    .matrix-load-status { padding: 12px 18px; color: var(--text-muted); }

    .admin-notice {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 18px;
      background-color: rgba(99,102,241,0.06);
      border-bottom: 1px solid rgba(99,102,241,0.15);
      font-size: 12px;
      color: var(--primary);
    }
    .admin-notice .icon { font-size: 18px; flex-shrink: 0; }

    /* Matrix Toolbar */
    .matrix-toolbar-box {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 12px 18px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-surface);
    }
    .search-and-expand-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .matrix-search-field {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 5px 10px;
      width: 380px;
      max-width: 100%;
    }
    .matrix-search-field .icon { font-size: 16px; color: var(--text-muted); }
    .matrix-search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 12px;
      color: var(--text-main);
      width: 100%;
    }
    .search-match-badge {
      font-size: 10px;
      font-weight: 500;
      color: var(--primary);
      background-color: rgba(99,102,241,0.1);
      padding: 1px 6px;
      border-radius: var(--radius-xs);
      white-space: nowrap;
    }
    .clear-search-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      padding: 0;
    }
    .clear-search-btn .material-symbols-outlined { font-size: 14px; }

    .expand-all-links { display: flex; align-items: center; gap: 6px; font-size: 11px; }
    .text-link {
      background: transparent;
      border: none;
      color: var(--primary);
      font-size: 11px;
      cursor: pointer;
      min-height: 28px;
      padding: 0 4px;
      display: inline-flex;
      align-items: center;
    }
    .text-link:hover { text-decoration: underline; }
    .link-sep { color: var(--text-light); }

    .module-filter-pills {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
    }
    .mod-pill-btn {
      border: 1px solid var(--border-color);
      background: var(--bg-hover);
      padding: 4px 10px;
      min-height: 28px;
      border-radius: 20px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      cursor: pointer;
      transition: all 0.1s ease;
      white-space: nowrap;
    }
    .mod-pill-btn:hover { color: var(--text-main); border-color: var(--text-muted); }
    .mod-pill-btn.active {
      color: #ffffff;
      background-color: var(--primary);
      border-color: var(--primary);
    }

    /* Modules Stack */
    .modules-stack {
      padding: 16px 18px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .mod-section-card {
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      overflow: hidden;
      background-color: var(--bg-surface);
    }
    .mod-section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 14px;
      background-color: var(--bg-hover);
      border-bottom: 1px solid var(--border-color);
      user-select: none;
    }
    .mod-header-left { display: flex; align-items: center; gap: 8px; }
    .mod-toggle-btn {
      min-width: 0;
      border: 0;
      background: transparent;
      color: inherit;
      cursor: pointer;
      font: inherit;
      padding: 0;
      text-align: left;
    }
    .chevron-icon { font-size: 18px; color: var(--text-muted); }
    .mod-icon { font-size: 18px; color: var(--primary); }
    .mod-title { font-size: 13px; font-weight: 600; color: var(--text-main); margin: 0; }
    .mod-count { font-size: 11px; color: var(--text-muted); }

    .mod-header-right { display: flex; align-items: center; gap: 8px; }
    .batch-btn {
      background: transparent;
      border: none;
      color: var(--primary);
      font-size: 11px;
      cursor: pointer;
      min-height: 28px;
      padding: 0 4px;
      display: inline-flex;
      align-items: center;
    }
    .batch-btn:hover { text-decoration: underline; }
    .batch-btn:disabled, .mini-toggle-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .batch-divider { color: var(--text-light); font-size: 10px; }

    .mod-section-body {
      padding: 0;
      overflow-x: auto;
    }
    .forms-grid-table {
      width: 100%;
      border-collapse: collapse;
    }
    .form-grid-row {
      border-bottom: 1px solid var(--border-color);
    }
    .form-grid-row:last-child { border-bottom: none; }
    .form-grid-row:hover { background-color: var(--bg-hover); }

    .form-title-col {
      padding: 12px 16px;
      width: 250px;
      vertical-align: top;
      border-right: 1px solid var(--border-color);
    }
    .form-title-wrap { display: flex; flex-direction: column; gap: 3px; }
    .form-name-text { font-size: 13px; font-weight: 600; color: var(--text-main); }
    .form-code-text { font-size: 10px; color: var(--text-muted); }

    .form-quick-toggles {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 10px;
      color: var(--text-light);
      margin-top: 4px;
    }
    .mini-toggle-btn {
      background: transparent;
      border: none;
      font-size: 10px;
      color: var(--primary);
      cursor: pointer;
      padding: 0;
    }
    .mini-toggle-btn:hover { text-decoration: underline; }
    .dot { color: var(--text-light); font-size: 8px; }

    .form-actions-col {
      padding: 12px 16px;
      vertical-align: middle;
    }
    .actions-chips-wrap {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .action-checkbox-card {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 12px;
      border-radius: var(--radius-xs);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      font-size: 12px;
      color: var(--text-muted);
      cursor: pointer;
      user-select: none;
      transition: all 0.1s ease;
    }
    .action-checkbox-card:hover { border-color: var(--text-muted); }
    .action-checkbox-card:focus-within {
      outline: 2px solid var(--primary);
      outline-offset: 1px;
    }
    .action-checkbox-card.checked {
      background-color: rgba(99,102,241,0.08);
      border-color: var(--primary);
      color: var(--text-main);
      font-weight: 500;
    }
    .action-checkbox-card.dirty {
      border-color: #f59e0b;
      box-shadow: 0 0 0 1px rgba(245, 158, 11, 0.4);
    }
    .dirty-indicator-dot {
      color: #f59e0b;
      font-weight: bold;
      font-size: 16px;
      line-height: 0;
      margin-left: -2px;
    }
    .action-checkbox-card.readonly { opacity: 0.9; cursor: not-allowed; }

    .chk-input { margin: 0; cursor: pointer; }
    .chk-label { font-size: 12px; }

    .no-forms-box {
      padding: 36px;
      text-align: center;
      color: var(--text-muted);
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }
    .no-forms-box .icon { font-size: 36px; color: var(--text-light); }

    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      border: 0;
    }
    .font-mono { font-family: monospace; }
  `]
})
export class RolePermissionsMatrixComponent {
  @Input({ required: true }) role!: Role;
  @Input() isLoading = false;
  @Input() isSaving = false;
  @Input() permissionsError = '';
  @Input() activePermissionsCount = 0;
  @Input() totalActionsCount = 0;
  @Input() permissionPercentage = 0;
  @Input() isPermissionsDirty = false;
  @Input() dirtyPermissionsCount = 0;
  @Input() canGrant = false;
  @Input() canEditPermissions = false;
  @Input() matrixSearchQuery = '';
  @Input() matchingFormsCount = 0;
  @Input() formsCount = 0;
  @Input() selectedModuleTab = 'all';
  @Input() moduleGroups: ModuleGroup[] = [];
  @Input() visibleModuleGroups: ModuleGroup[] = [];

  @Input() hasPermission!: (formCode: string, action: string) => boolean;
  @Input() isPermissionDirty!: (formCode: string, action: string) => boolean;
  @Input() getModuleIcon!: (moduleCode: string) => string;
  @Input() getModuleActionsCount!: (mod: ModuleGroup) => number;

  @Output() resetChanges = new EventEmitter<void>();
  @Output() savePermissions = new EventEmitter<void>();
  @Output() refreshRole = new EventEmitter<Role>();
  @Output() matrixSearchQueryChange = new EventEmitter<string>();
  @Output() selectedModuleTabChange = new EventEmitter<string>();
  @Output() setAllModulesExpanded = new EventEmitter<boolean>();
  @Output() toggleAllPermissions = new EventEmitter<boolean>();
  @Output() toggleReadOnlyAllPermissions = new EventEmitter<void>();
  @Output() toggleModuleExpand = new EventEmitter<ModuleGroup>();
  @Output() toggleReadOnlyModule = new EventEmitter<ModuleGroup>();
  @Output() toggleAllModule = new EventEmitter<{ mod: ModuleGroup; select: boolean }>();
  @Output() toggleAllForm = new EventEmitter<{ form: GroupedForm; select: boolean }>();
  @Output() togglePermission = new EventEmitter<{ formCode: string; action: string; event: Event }>();
}
