import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { Role } from '@core/models/rbac.models';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-role-cards-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, TranslatePipe],
  template: `
    <div class="roles-strip-container">
      <div class="roles-strip-header">
        <span class="strip-title">{{ 'iam.roles.select_role_hint' | t }}</span>
        <div class="search-field">
          <label class="sr-only" for="role-search">{{ 'iam.roles.search' | t }}</label>
          <smt-input
            smtFieldId="role-search"
            name="roleSearch"
            type="search"
            smtIcon="search"
            clearable
            smtSize="sm"
            [placeholder]="'iam.roles.filter_roles' | t"
            [value]="searchQuery()"
            (valueChange)="searchQueryChange.emit($event === null ? '' : '' + $event)"
          />
        </div>
      </div>

      <div class="roles-cards-grid">
        @for (r of roles(); track r) {
          <div class="role-card-btn" [class.active]="selectedRole()?.id === r.id">
            <button
              type="button"
              class="role-select-btn"
              [attr.aria-label]="'iam.select_role_named' | t: { name: r.name }"
              [attr.aria-pressed]="selectedRole()?.id === r.id"
              [disabled]="isSaving() || scopePanelBusy() || isSubmittingRole()"
              (click)="selectRole.emit(r)"
            >
              <span class="role-card-head">
                <span class="role-card-title">{{ r.name }}</span>
                @if (r.pcode) {
                  <span class="role-sys-tag font-mono">{{ r.pcode }}</span>
                }
                @if (!r.pcode) {
                  <span class="role-custom-tag">{{ 'iam.roles.custom' | t }}</span>
                }
              </span>
              <div class="role-status-line">
                <span class="status-dot" aria-hidden="true" [class.active]="r.state === 'A'"></span>
                <span class="status-text">{{
                  (r.state === 'A' ? 'common.active_feminine' : 'common.disabled_feminine') | t
                }}</span>
              </div>
            </button>

            <div class="role-card-foot">
              <button
                type="button"
                class="role-users-btn"
                [attr.aria-label]="'iam.roles.users_in_role' | t: { name: r.name, count: roleUserCounts()[r.id] || 0 }"
                [title]="'iam.roles.users_in_role' | t: { name: r.name, count: roleUserCounts()[r.id] || 0 }"
                (click)="navigateToUsers.emit({ role: r, event: $event })"
              >
                <span class="material-symbols-outlined users-icon" aria-hidden="true">group</span>
                <span>{{ roleUserCounts()[r.id] || 0 }}</span>
              </button>

              <div class="role-btns">
                @if (canUpdateRole()) {
                  <button
                    type="button"
                    class="mini-btn"
                    [attr.aria-label]="'iam.edit_role_named' | t: { name: r.name }"
                    [title]="'iam.roles.edit_role' | t"
                    (click)="openEdit.emit(r)"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                  </button>
                }
                @if (!r.pcode && canDeleteRole()) {
                  <button
                    type="button"
                    class="mini-btn delete"
                    [attr.aria-label]="'iam.delete_role_named' | t: { name: r.name }"
                    [title]="'iam.roles.delete_role' | t"
                    [disabled]="isSaving() || scopePanelBusy() || isSubmittingRole()"
                    (click)="openDelete.emit(r)"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">delete</span>
                  </button>
                }
              </div>
            </div>
          </div>
        }

        @if (canCreateRole()) {
          <button type="button" class="add-role-dashed-btn" (click)="openCreate.emit()">
            <span class="material-symbols-outlined" aria-hidden="true">add</span>
            <span>{{ 'iam.roles.create_role' | t }}</span>
          </button>
        }
      </div>
    </div>
  `,
  styleUrl: './role-cards-bar.component.css',
})
export class RoleCardsBarComponent {
  readonly roles = input<Role[]>([]);
  readonly selectedRole = input<Role | null>(null);
  readonly roleUserCounts = input<Record<number, number>>({});
  readonly searchQuery = input('');
  readonly isSaving = input(false);
  readonly scopePanelBusy = input(false);
  readonly isSubmittingRole = input(false);
  readonly canCreateRole = input(false);
  readonly canUpdateRole = input(false);
  readonly canDeleteRole = input(false);

  readonly searchQueryChange = output<string>();
  readonly selectRole = output<Role>();
  readonly navigateToUsers = output<{
    role: Role;
    event: MouseEvent;
  }>();
  readonly openEdit = output<Role>();
  readonly openDelete = output<Role>();
  readonly openCreate = output<void>();
}
