/**
 * The field errors of a refused request (application/problem+json, ADR-0021), by the field of the form they belong
 * to (docs/guidelines/forms-ux-standard.md, section 5). The server names a field in `errors[].field` (`name`,
 * `attributes.color`, `lines[2].qty`) or, from bean validation, in `invalid_params[].name`; a JSON pointer
 * (`/lines/2/qty`) is read the same way. Each field keeps its first message, already in the request's language.
 *
 * A screen hands `fields` to its `smt-control [smtError]`s and shows `other` (errors of fields it does not draw)
 * in the form's error summary or alert, so no message is lost.
 */
export interface ProblemFieldErrors {
  /** Message by form field. */
  readonly fields: Readonly<Record<string, string>>;
  /** Messages of fields the form does not know, in the server's order. */
  readonly other: readonly string[];
}

export interface ProblemFieldOptions {
  /** The fields the form draws; any field is accepted when absent. */
  readonly known?: readonly string[];
  /** A server field name -> the form's field name, where they differ (`ownerOrg` -> `owner`). */
  readonly rename?: Readonly<Record<string, string>>;
}

interface RawFieldError {
  field: string;
  message: string;
}

const EMPTY: ProblemFieldErrors = Object.freeze({ fields: Object.freeze({}), other: Object.freeze([]) });

/** `/lines/2/qty` -> `lines[2].qty`; a plain field name stays as it is. */
export function fieldFromPointer(pointer: string): string {
  if (!pointer.startsWith('/')) return pointer;
  return pointer
    .slice(1)
    .split('/')
    .map((part) => part.replace(/~1/g, '/').replace(/~0/g, '~'))
    .reduce((path, part) => (/^\d+$/.test(part) ? `${path}[${part}]` : path ? `${path}.${part}` : part), '');
}

function rawErrors(problem: unknown): RawFieldError[] {
  if (typeof problem !== 'object' || problem === null) return [];
  const { errors, invalid_params: invalidParams } = problem as { errors?: unknown; invalid_params?: unknown };
  const found: RawFieldError[] = [];
  for (const item of Array.isArray(errors) ? errors : []) {
    const { field, pointer, message } = (item ?? {}) as { field?: unknown; pointer?: unknown; message?: unknown };
    const name = typeof field === 'string' ? field : typeof pointer === 'string' ? pointer : '';
    if (name && typeof message === 'string') found.push({ field: fieldFromPointer(name), message });
  }
  for (const item of Array.isArray(invalidParams) ? invalidParams : []) {
    const { name, reason } = (item ?? {}) as { name?: unknown; reason?: unknown };
    if (typeof name === 'string' && typeof reason === 'string')
      found.push({ field: fieldFromPointer(name), message: reason });
  }
  return found;
}

export function problemFieldErrors(problem: unknown, options: ProblemFieldOptions = {}): ProblemFieldErrors {
  const raw = rawErrors(problem);
  if (raw.length === 0) return EMPTY;
  const known = options.known ? new Set(options.known) : null;
  const fields: Record<string, string> = {};
  const other: string[] = [];
  for (const { field, message } of raw) {
    const name = options.rename?.[field] ?? field;
    if (known && !known.has(name)) {
      other.push(message);
    } else if (!(name in fields)) {
      fields[name] = message;
    }
  }
  return { fields, other };
}
