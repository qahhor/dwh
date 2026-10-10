import { problemFieldErrors, ProblemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';

/**
 * Refusals of a password request that name no field but belong to one (ADR-0021): the policy check of a new password
 * and a wrong current password. Their text goes under that field instead of above the form.
 */
const FIELD_BY_CODE: Readonly<Record<string, string>> = {
  password_policy: 'newPassword',
  invalid_credentials: 'oldPassword',
};

/**
 * The field errors of a refused password request (docs/guidelines/forms-ux-standard.md, section 5): the fields the
 * server named (`errors[].field`), otherwise the field the refusal's code belongs to. Messages of fields the form
 * does not draw come back in `other`; an error of no field gives nothing, and the form shows it above the fields.
 */
export function passwordProblemFields(problem: unknown, known: readonly string[]): ProblemFieldErrors {
  const named = problemFieldErrors(problem, { known });
  if (Object.keys(named.fields).length > 0 || named.other.length > 0) return named;
  const code = typeof problem === 'object' && problem !== null ? (problem as { code?: unknown }).code : undefined;
  const field = typeof code === 'string' ? FIELD_BY_CODE[code.toLowerCase()] : undefined;
  const detail = problemText(problem).trim();
  if (field && known.includes(field) && detail) return { fields: { [field]: detail }, other: [] };
  return { fields: {}, other: [] };
}

/** The server's words for a refusal, or `fallback` when it gave none. */
export function problemMessage(problem: unknown, fallback: string): string {
  if (problem && typeof problem === 'object') {
    const value = problem as { detail?: unknown; message?: unknown };
    if (typeof value.detail === 'string' && value.detail.trim()) return value.detail;
    if (typeof value.message === 'string' && value.message.trim()) return value.message;
  }
  return fallback;
}
