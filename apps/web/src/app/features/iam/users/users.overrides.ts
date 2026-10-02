import { EnvironmentProviders } from '@angular/core';
import { provideEntityOverrides } from '@shared/entity/page/entity-overrides';
import { UserAccessTabComponent } from './components/user-access-tab.component';
import { UserSecurityTabComponent } from './components/user-security-tab.component';
import { UserSettingFieldComponent } from './components/user-setting-field.component';

/** The code of the user entity on the server (ADR-0032 8). */
export const USERS_ENTITY = 'md.users';

/**
 * The user accounts on the general entity screen `/e/md.users` (ADR-0032 7.2 and 8): the platform draws the list, the
 * form, the record, its actions and history; the accounts add the language and time zone choices of the form and two
 * tabs of the record — sessions and security, roles and rights — each shown to holders of its right.
 */
export function provideUserScreen(): EnvironmentProviders {
  return provideEntityOverrides(USERS_ENTITY, {
    fields: { language: UserSettingFieldComponent, timezone: UserSettingFieldComponent },
    tabs: [
      {
        key: 'security',
        labelKey: 'iam.users.tab.security',
        component: UserSecurityTabComponent,
        requires: [{ form: USERS_ENTITY, action: 'view' }],
      },
      {
        key: 'access',
        labelKey: 'iam.users.tab.access',
        component: UserAccessTabComponent,
        requires: [
          { form: 'md.org_units', action: 'view' },
          { form: 'md.assignments', action: 'view' },
        ],
      },
    ],
  });
}
