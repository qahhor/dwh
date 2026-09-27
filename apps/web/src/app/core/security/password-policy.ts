/**
 * Length of a new password, as the server checks it (PasswordValidator): 8 to 20 characters, decision of 2026-09-27.
 * Only a new password is checked; signing in with an older, longer one keeps working.
 */
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 20;

/** Parameters of the `password.policy.*` strings. */
export const PASSWORD_POLICY = { min: PASSWORD_MIN_LENGTH, max: PASSWORD_MAX_LENGTH } as const;

export function fitsPasswordPolicy(password: string | null | undefined): boolean {
  const length = (password ?? '').length;
  return length >= PASSWORD_MIN_LENGTH && length <= PASSWORD_MAX_LENGTH;
}
