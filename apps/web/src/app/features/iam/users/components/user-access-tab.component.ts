import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import type { FormMeta } from '@core/models/form-meta.models';
import { PermissionService } from '@core/services/permission.service';
import { RolesApi } from '@core/services/roles.api';
import { UserOrgUnitsPanelComponent } from '@features/iam/org-units/public-api';
import type { EntityRecord } from '@shared/entity/entities.api';
import { UserEffectivePermissionsPanelComponent } from './user-effective-permissions-panel.component';

/**
 * The tab "Roles and rights" of a user on the general record page (ADR-0032 7.2, `provideEntityOverrides`): the units
 * the user is assigned to and the effective rights from roles and personal grants. Both are assignments of their own
 * (`md.org_units`, `md.assignments`), saved from the user's revision (plan 10/10, item 3.6), so neither is a field of
 * the record (ADR-0032 8); each part is shown to holders of its right only.
 */
@Component({
  selector: 'app-user-access-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [UserOrgUnitsPanelComponent, UserEffectivePermissionsPanelComponent],
  template: `
    <div class="user-access-tab" data-testid="user-access-tab">
      @if (canViewUnits()) {
        <app-user-org-units-panel
          [userId]="record().id"
          [revision]="revision()"
          (revisionChange)="revision.set($event)"
        />
      }
      @if (canViewRights()) {
        <app-user-effective-permissions-panel
          [userId]="record().id"
          [revision]="revision()"
          [canAssign]="canAssign()"
          [userRoleNames]="roleNames()"
          (revisionChange)="revision.set($event)"
        />
      }
    </div>
  `,
  styles: [
    `
      .user-access-tab {
        display: flex;
        flex-direction: column;
        gap: 24px;
      }
    `,
  ],
})
export class UserAccessTabComponent {
  private readonly permissions = inject(PermissionService);
  private readonly roles = inject(RolesApi);

  readonly record = input.required<EntityRecord>();
  readonly meta = input<FormMeta | null>(null);

  /** The user's revision as the assignments moved it; a new read of the record starts from its own. */
  readonly revision = linkedSignal(() => this.record().revision);

  readonly canViewUnits = computed(() => this.permissions.hasPermission('md.org_units', 'view'));
  readonly canViewRights = computed(() => this.permissions.hasPermission('md.assignments', 'view'));
  readonly canAssign = computed(() => this.permissions.hasPermission('md.assignments', 'assign'));

  /** The names of the user's roles, from the role ids the record carries for holders of the assignments' right. */
  readonly roleNames = computed(() => {
    const ids = Array.isArray(this.record()['roleIds']) ? (this.record()['roleIds'] as number[]) : [];
    const roles = this.allRoles.hasValue() ? this.allRoles.value() : [];
    return roles.filter((role) => ids.includes(role.id)).map((role) => role.name);
  });

  private readonly allRoles = rxResource({
    // The names of roles come from the roles' list, which needs its own right.
    params: () => (this.canViewRights() && this.permissions.hasPermission('md.roles', 'view') ? true : undefined),
    stream: () => this.roles.list().pipe(catchError(() => of([]))),
  });
}
