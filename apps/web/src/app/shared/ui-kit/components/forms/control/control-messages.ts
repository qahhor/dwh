/* Our code, after the idea of the kit's `smt-control` (smartup-ui-kit@6472beb,
 * components/forms/control). The kit hard-coded English messages for the
 * reactive-forms error keys only; this maps both Signal Forms errors and
 * legacy `NgControl` errors to catalogue strings. See ADR-0015. */

/** One validation error, whichever forms API produced it. */
export interface SMTControlError {
  readonly kind: string;
  /** A message the validator supplied; shown as is. */
  readonly message?: string;
  readonly minLength?: number;
  readonly maxLength?: number;
  readonly min?: number | string;
  readonly max?: number | string;
}

export interface SMTControlMessages {
  readonly required: string;
  readonly email: string;
  readonly pattern: string;
  readonly parse: string;
  readonly invalid: string;
  readonly minLength: (count: number) => string;
  readonly maxLength: (count: number) => string;
  readonly min: (value: string) => string;
  readonly max: (value: string) => string;
}

/**
 * Converts the error map of a template-driven or reactive control into the
 * shape Signal Forms uses, so one message table serves both.
 */
export function fromLegacyErrors(errors: Readonly<Record<string, unknown>>): SMTControlError[] {
  return Object.entries(errors).map(([key, detail]) => {
    const value = (detail ?? {}) as Record<string, unknown>;
    switch (key) {
      case 'minlength':
        return { kind: 'minLength', minLength: Number(value['requiredLength']) };
      case 'maxlength':
        return { kind: 'maxLength', maxLength: Number(value['requiredLength']) };
      case 'min':
        return { kind: 'min', min: value['min'] as number };
      case 'max':
        return { kind: 'max', max: value['max'] as number };
      default:
        return typeof detail === 'string' ? { kind: key, message: detail } : { kind: key };
    }
  });
}

/** The text shown under the field for one error. */
export function messageForError(error: SMTControlError | undefined, messages: SMTControlMessages): string {
  if (!error) return '';
  if (error.message) return error.message;

  switch (error.kind) {
    case 'required':
      return messages.required;
    case 'email':
      return messages.email;
    case 'pattern':
      return messages.pattern;
    case 'parse':
      return messages.parse;
    case 'minLength':
      return error.minLength !== undefined ? messages.minLength(error.minLength) : messages.invalid;
    case 'maxLength':
      return error.maxLength !== undefined ? messages.maxLength(error.maxLength) : messages.invalid;
    case 'min':
      return error.min !== undefined ? messages.min(String(error.min)) : messages.invalid;
    case 'max':
      return error.max !== undefined ? messages.max(String(error.max)) : messages.invalid;
    default:
      return messages.invalid;
  }
}
