/** Bounded field paths and translated reasons from the standard Problem Details response. */
export function studioProblemIssues(error: unknown): { path: string; message: string }[] {
  const problem =
    typeof error === 'object' && error !== null ? (error as { errors?: unknown; invalid_params?: unknown }) : {};
  const errors = Array.isArray(problem.errors) ? problem.errors : problem.invalid_params;
  return (Array.isArray(errors) ? errors : []).slice(0, 20).flatMap((item) => {
    if (typeof item !== 'object' || item === null) return [];
    const path = item.field ?? item.name;
    const message = item.message ?? item.reason;
    return typeof path === 'string' && typeof message === 'string'
      ? [{ path: path.slice(0, 160), message: message.slice(0, 500) }]
      : [];
  });
}
