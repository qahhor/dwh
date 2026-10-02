import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { UserSecuritySummary } from '@core/models/auth.models';
import type { FormMeta } from '@core/models/form-meta.models';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { formField } from '@testing/form-meta';
import { entityRecord, renderEntityScreen } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';
import { USERS_ENTITY, provideUserScreen } from './users.overrides';

/*
 * The user accounts on the general entity screen (ADR-0032 7.2 and 8): the platform draws the record and its actions,
 * the accounts add the language and time zone choices and the tabs of sessions and security, roles and rights.
 */
const META: FormMeta = {
  code: USERS_ENTITY,
  listCode: USERS_ENTITY,
  fields: [
    formField('name', 'text', { labelKey: 'iam.users.col.name', required: true }),
    formField('login', 'text', { labelKey: 'iam.users.col.login', required: true }),
    formField('language', 'text', { labelKey: 'iam.common.language' }),
    formField('timezone', 'text', { labelKey: 'iam.common.time_zone' }),
  ],
  layout: [
    { key: 'profile', labelKey: 'iam.users.section.profile', fields: ['name', 'login'] },
    { key: 'work', labelKey: 'iam.users.section.work', fields: ['language', 'timezone'] },
  ],
  actions: ['create', 'update', 'block', 'unblock', 'reset_2fa', 'anonymize'],
  capabilities: ['history', 'export', 'saved_views'],
};

const ANNA = entityRecord(5, {
  name: 'Анна',
  login: 'anna',
  language: 'ru',
  timezone: 'Asia/Tashkent',
  revision: 3,
  roleIds: [],
  actions: ['update', 'block', 'anonymize'],
});

const SUMMARY: UserSecuritySummary = {
  userId: 5,
  login: 'anna',
  is2faEnabled: true,
  forcePasswordChange: false,
  createdAt: '2026-10-01T09:00:00Z',
  authVersion: 2,
  activeSessionsCount: 1,
  activeSessions: [
    {
      id: 11,
      userId: 5,
      ip: '10.0.0.5',
      userAgent: 'Browser',
      deviceInfo: 'test',
      createdAt: '2026-10-01T09:00:00Z',
      lastSeenAt: '2026-10-01T10:00:00Z',
    },
  ],
  recentLoginAttempts: [],
};

function permissions(held: string[]) {
  return {
    provide: PermissionService,
    useValue: { hasPermission: (form: string, action: string) => held.includes(`${form}.${action}`) },
  };
}

async function renderUser(url: string, held: string[]) {
  return renderEntityScreen(url, {
    meta: META,
    records: [ANNA],
    answers: {
      '/iam/users/5/security': SUMMARY,
      '/iam/users/5/effective-permissions': { items: [] },
      '/iam/users/5/permissions': { grants: [] },
      '/iam/forms': [],
      '/iam/org-units': [],
      '/iam/org-units/users/5': { userId: 5, orgUnitIds: [], legacyOrgUnitId: null, revision: 3 },
      '/iam/roles': [],
    },
    providers: [provideUserScreen(), permissions(held)],
  });
}

describe('the user accounts on the general screen /e/md.users', () => {
  it('offers the actions of the record and the tabs the viewer may open', async () => {
    const { root } = await renderUser(`/e/${USERS_ENTITY}/5`, ['md.users.view', 'md.users.block']);

    const actions = Array.from(root.querySelectorAll<HTMLButtonElement>('[data-action]')).map((button) => ({
      code: button.dataset['action'],
      label: button.textContent?.trim(),
    }));
    expect(actions).toEqual([
      { code: 'block', label: translateTest('entity.action.block') },
      { code: 'anonymize', label: translateTest('entity.action.anonymize') },
    ]);
    const tabs = Array.from(root.querySelectorAll('[role="tab"]')).map((tab) => tab.textContent?.trim());
    expect(tabs).toContain(translateTest('iam.users.tab.security'));
    expect(tabs).not.toContain(translateTest('iam.users.tab.access'));
  });

  it('shows the sessions and closes them only for a holder of the right to block', async () => {
    const { root, settle } = await renderUser(`/e/${USERS_ENTITY}/5`, ['md.users.view', 'md.users.block']);

    const security = Array.from(root.querySelectorAll<HTMLElement>('[role="tab"]')).find(
      (tab) => tab.textContent?.trim() === translateTest('iam.users.tab.security'),
    )!;
    security.click();
    await settle();

    const tab = root.querySelector('[data-testid="user-security-tab"]')!;
    expect(tab.textContent).toContain('v2');
    expect(tab.textContent).toContain('10.0.0.5');
    expect(root.querySelector('[data-testid="user-close-sessions"]')).not.toBeNull();
  });

  it('opens the roles and rights tab for a holder of the assignments right', async () => {
    const { root, settle } = await renderUser(`/e/${USERS_ENTITY}/5`, ['md.users.view', 'md.assignments.view']);

    const access = Array.from(root.querySelectorAll<HTMLElement>('[role="tab"]')).find(
      (tab) => tab.textContent?.trim() === translateTest('iam.users.tab.access'),
    )!;
    access.click();
    await settle();

    expect(root.querySelector('[data-testid="user-access-tab"] app-user-effective-permissions-panel')).not.toBeNull();
    expect(root.querySelector('[data-testid="user-access-tab"] app-user-org-units-panel')).toBeNull();
  });

  it('chooses the language among the active languages and the time zone among the usual ones', async () => {
    const { root, settle } = await renderUser(`/e/${USERS_ENTITY}/5/edit`, ['md.users.view', 'md.users.update']);
    TestBed.inject(I18nService).languages.set([
      { code: 'ru', name: 'Русский', builtin: true, active: true, revision: 1, translated: 1 },
      { code: 'uz', name: 'Oʻzbekcha', builtin: true, active: true, revision: 1, translated: 1 },
      { code: 'kk', name: 'Қазақша', builtin: false, active: false, revision: 1, translated: 1 },
    ] as never);
    await settle();

    const language = root.querySelector<HTMLButtonElement>('#user-setting-language')!;
    expect(language).not.toBeNull();
    expect(language.textContent).toContain('Русский (ru)');
    expect(root.querySelector('#user-setting-timezone')?.textContent).toContain('Asia/Tashkent');
    // The language field takes no text: it is a choice.
    expect(root.querySelector('[data-field="language"] input[type="text"]')).toBeNull();
  });
});
