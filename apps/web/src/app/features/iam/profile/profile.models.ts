import { PASSWORD_MIN_LENGTH } from '@core/security/password-policy';

export type { User, UserSession, ApiToken, CreatedTokenResponse } from '@core/models/auth.models';

export interface UserChannel {
  id: number;
  userId: number;
  channel: string;
  address: string;
  isVerified: boolean;
  createdAt: string;
}

export interface BindChannelResponse {
  verifyToken: string;
}

export interface PasswordForm {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export interface PasswordStrength {
  score: number;
  label: string;
  percent: number;
  colorClass: string;
}

export interface TokenExpirationOption {
  value: string;
  labelKey: string;
}

/** One point each for length, mixed case, a digit and a symbol; the meter shows the total. */
export function passwordStrengthOf(pwd: string): PasswordStrength {
  if (!pwd) return { score: 0, label: '', percent: 0, colorClass: '' };
  let score = 0;
  if (pwd.length >= PASSWORD_MIN_LENGTH) score++;
  if (/[a-z\u0430-\u044f]/.test(pwd) && /[A-Z\u0410-\u042f]/.test(pwd)) score++;
  if (/\d/.test(pwd)) score++;
  if (/[^a-zA-ZЀ-ӿ0-9]/.test(pwd)) score++;

  let label = 'iam.parol_slabyy';
  let colorClass = 'strength-weak';
  let percent = 25;

  if (score === 2) {
    label = 'iam.parol_sredniy';
    colorClass = 'strength-medium';
    percent = 50;
  } else if (score === 3) {
    label = 'iam.parol_horoshiy';
    colorClass = 'strength-good';
    percent = 75;
  } else if (score >= 4) {
    label = 'iam.parol_otlichnyy';
    colorClass = 'strength-strong';
    percent = 100;
  }

  return { score, label, percent, colorClass };
}

/** When a token picked with `option` (days, or `never`) expires, counted from `now`; null never expires. */
export function tokenExpiresAt(option: string, now: Date): string | null {
  const days = option === '30' ? 30 : option === '90' ? 90 : option === '365' ? 365 : 0;
  return days ? new Date(now.getTime() + days * 86400000).toISOString() : null;
}
