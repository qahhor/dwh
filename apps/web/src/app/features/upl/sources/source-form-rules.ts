import {
  PathKind,
  SchemaPath,
  SchemaPathRules,
  ValidationError,
  maxLength,
  required,
  validate,
} from '@angular/forms/signals';

/** The longest source name the server accepts. */
export const UPL_SOURCE_NAME_MAX_LENGTH = 200;
/** The longest owner and contact texts. */
const OWNER_MAX_LENGTH = 200;
const SLA_MIN = 0;
const SLA_MAX = 366;

type ChildPath<T> = SchemaPath<T, SchemaPathRules.Supported, PathKind.Child>;

/** The requisites checked the same way in the "new source" window and on the source card. */
export interface UplSourceRequisitePaths {
  name: ChildPath<string>;
  ownerOrg: ChildPath<string>;
  ownerContact: ChildPath<string>;
  slaDays: ChildPath<number | null>;
}

/** Turns an i18n key into the error smt-control shows as is; read in a reactive context, so it follows the language. */
export type UplRuleMessage = (key: string) => ValidationError.WithoutFieldTree;

/**
 * Name and owner are required (smt-control marks them "*"), and a text of spaces counts as empty; the name fits the
 * server's limit, and the SLA is a whole number of days within a year.
 */
export function uplSourceRequisiteRules(path: UplSourceRequisitePaths, message: UplRuleMessage): void {
  uplRequiredText(path.name, message);
  uplRequiredText(path.ownerOrg, message);
  validate(path.name, ({ value }) =>
    value().trim().length > UPL_SOURCE_NAME_MAX_LENGTH ? message('upl.source.err.name_length') : null,
  );
  validate(path.slaDays, ({ value }) => {
    const raw = value();
    const days = Number(raw);
    return raw === null || !Number.isInteger(days) || days < SLA_MIN || days > SLA_MAX
      ? message('upl.source.err.sla_range')
      : null;
  });
}

/** A required text: the built-in rule (empty, and the "*" mark) plus a check that a text of spaces is empty too. */
export function uplRequiredText(path: ChildPath<string>, message: UplRuleMessage): void {
  required(path, { error: () => message('upl.source.err.required') });
  validate(path, ({ value }) =>
    value().length > 0 && value().trim().length === 0 ? message('upl.source.err.required') : null,
  );
}

/**
 * Length limits, always on (not only after a save): through `[formField]` they set the fields' native
 * maxlength, which the templates used to bind directly, so a person cannot type past them.
 */
export function uplSourceLengthLimits(path: UplSourceRequisitePaths): void {
  maxLength(path.name, UPL_SOURCE_NAME_MAX_LENGTH);
  maxLength(path.ownerOrg, OWNER_MAX_LENGTH);
  maxLength(path.ownerContact, OWNER_MAX_LENGTH);
}
