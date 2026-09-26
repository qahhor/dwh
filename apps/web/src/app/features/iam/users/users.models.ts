import { User } from '../../../core/models/auth.models';
import { Role } from '../../../core/models/rbac.models';
import { ToastService } from '../../../core/services/toast.service';
import { I18nService } from '../../../core/services/i18n.service';

export interface UserCreateForm {
  name: string;
  login: string;
  email: string;
  phone: string;
  password: string;
  managerId: number | null;
  language: string;
  timezone: string;
  is2faEnabled: boolean;
  roleIds: number[];
  attributes: Record<string, unknown>;
}

export interface UserEditForm {
  name: string;
  phone: string;
  managerId: number | null;
  language: string;
  timezone: string;
  is2faEnabled: boolean;
  roleIds: number[];
  attributes: Record<string, unknown>;
}

export function createDefaultUserCreateForm(defaultRoleIds: number[] = []): UserCreateForm {
  return {
    name: '',
    login: '',
    email: '',
    phone: '',
    password: '',
    managerId: null,
    language: 'ru',
    timezone: 'Asia/Tashkent',
    is2faEnabled: false,
    roleIds: [...defaultRoleIds],
    attributes: {}
  };
}

export function createDefaultUserEditForm(user: User): UserEditForm {
  return {
    name: user.name,
    phone: user.phone || '',
    managerId: user.managerId || null,
    language: user.language || 'ru',
    timezone: user.timezone || 'Asia/Tashkent',
    is2faEnabled: !!user.is2faEnabled,
    roleIds: user.roleIds ? [...user.roleIds] : [],
    attributes: { ...(user.attributes || {}) }
  };
}

/** The manager's name, or their id while the name is unknown. */
export function getManagerName(user: User, nameOf: (id: number) => string | null): string | null {
  if (!user.managerId) return null;
  return nameOf(user.managerId) ?? `ID: #${user.managerId}`;
}

export function getUserRoleNames(user: User, allRoles: Role[]): string[] {
  if (!user.roleIds || user.roleIds.length === 0) return [];
  return user.roleIds
    .map(id => allRoles.find(r => r.id === id)?.name)
    .filter((name): name is string => !!name);
}

export function generateSecurePassword(login?: string): string {
  const uppers = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
  const lowers = 'abcdefghijkmnpqrstuvwxyz';
  const digits = '23456789';
  const symbols = '!@#$%&*';
  const allChars = uppers + lowers + digits + symbols;

  const getRandom = (charset: string) => {
    if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
      const array = new Uint32Array(1);
      crypto.getRandomValues(array);
      return charset[array[0] % charset.length];
    }
    return charset[Math.floor(Math.random() * charset.length)];
  };

  let generated = '';
  const normalizedLogin = (login || '').trim().toLowerCase();

  for (let attempt = 0; attempt < 10; attempt++) {
    const pwdChars: string[] = [
      getRandom(uppers),
      getRandom(uppers),
      getRandom(lowers),
      getRandom(lowers),
      getRandom(digits),
      getRandom(digits),
      getRandom(symbols),
      getRandom(symbols)
    ];

    while (pwdChars.length < 14) {
      pwdChars.push(getRandom(allChars));
    }

    for (let i = pwdChars.length - 1; i > 0; i--) {
      const j = typeof crypto !== 'undefined' && crypto.getRandomValues
        ? (() => { const a = new Uint32Array(1); crypto.getRandomValues(a); return a[0] % (i + 1); })()
        : Math.floor(Math.random() * (i + 1));
      [pwdChars[i], pwdChars[j]] = [pwdChars[j], pwdChars[i]];
    }

    const candidate = pwdChars.join('');
    if (!normalizedLogin || normalizedLogin.length < 3 || !candidate.toLowerCase().includes(normalizedLogin)) {
      generated = candidate;
      break;
    }
  }

  if (!generated) {
    generated = 'K9#mX2$vL5@wP8';
  }

  return generated;
}

export async function copyPasswordToClipboard(
  password: string,
  toast: ToastService,
  uiI18n: I18nService
): Promise<void> {
  if (!password) return;
  try {
    if (typeof navigator !== 'undefined' && navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(password);
    }
    toast.success(uiI18n.translate('iam.parol_skopirovan_v_bufer'));
  } catch {
    toast.info(password);
  }
}

export function hasMinLength(password?: string): boolean {
  return (password || '').length >= 10;
}

export function hasUpperAndLower(password?: string): boolean {
  const pwd = password || '';
  return /[a-z\u0430-\u044F\u0451]/.test(pwd) && /[A-Z\u0410-\u042F\u0401]/.test(pwd);
}

export function hasDigitsOrSymbols(password?: string): boolean {
  const pwd = password || '';
  return /[0-9]/.test(pwd) || /[^a-zA-Z0-9\u0430-\u044F\u0410-\u042F\u0451\u0401]/.test(pwd);
}

export function doesNotContainLogin(password?: string, login?: string): boolean {
  const pwd = (password || '').toLowerCase();
  const normalizedLogin = (login || '').trim().toLowerCase();
  if (!normalizedLogin || normalizedLogin.length < 3) return true;
  return !pwd.includes(normalizedLogin);
}

export function calculatePasswordStrength(
  password: string | undefined,
  login: string | undefined,
  uiI18n: I18nService
): { score: number; label: string; color: string } {
  const pwd = password || '';
  const normalizedLogin = login || '';
  let score = 0;
  if (pwd.length >= 10) score++;
  if (/[a-z\u0430-\u044F\u0451]/.test(pwd) && /[A-Z\u0410-\u042F\u0401]/.test(pwd)) score++;
  if (/[0-9]/.test(pwd) && /[^a-zA-Z0-9\u0430-\u044F\u0410-\u042F\u0451\u0401]/.test(pwd)) score++;
  else if (/[0-9]/.test(pwd) || /[^a-zA-Z0-9\u0430-\u044F\u0410-\u042F\u0451\u0401]/.test(pwd)) score += 0.5;
  if (normalizedLogin && normalizedLogin.length >= 3 && !pwd.toLowerCase().includes(normalizedLogin.toLowerCase())) score += 0.5;

  if (score < 1.5) return { score: 1, label: uiI18n.translate('iam.parol_slabyy'), color: 'var(--danger)' };
  if (score < 2.5) return { score: 2, label: uiI18n.translate('iam.parol_sredniy'), color: 'var(--warning)' };
  if (score < 3.5) return { score: 3, label: uiI18n.translate('iam.parol_horoshiy'), color: 'var(--info)' };
  return { score: 4, label: uiI18n.translate('iam.parol_otlichnyy'), color: 'var(--success)' };
}

export interface EffectivePermissionItem {
  form: string;
  action: string;
  source: 'role' | 'personal';
}

export interface PersonalGrant {
  form: string;
  action: string;
}

export interface EffectivePermissionsResponse {
  items: EffectivePermissionItem[];
}

export interface PersonalPermissionsResponse {
  grants: PersonalGrant[];
}

