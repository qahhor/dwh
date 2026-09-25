/**
 * The words to show when an action confirmed in a dialog fails: the server's
 * problem detail (ApiService maps every error to one), or nothing, so the
 * dialog falls back to its generic message.
 */
export function problemText(error: unknown): string {
  if (typeof error !== 'object' || error === null) return '';
  const detail = (error as { detail?: unknown }).detail;
  return typeof detail === 'string' ? detail : '';
}
