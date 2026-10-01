import { describe, expect, it } from 'vitest';

import type { FieldErrorItem, ProblemDetail } from '../models/common.models';
import type { FormFieldMeta } from '../models/form-meta.models';
import type { QueryFieldMeta } from '../models/query-meta.models';
import type { ApiSchema } from './api-schema';

/** Keys the server sends that a hand-written web type does not know; `never` when the web type keeps up. */
type Unknown<Server, Web> = Exclude<keyof Server, keyof Web>;

describe('ApiSchema', () => {
  it('keeps the web error model in step with the problem details the server describes', () => {
    // A field added to ProblemDetailRecord on the server fails typecheck here until the web type names it.
    const problem: [Unknown<ApiSchema<'ProblemDetailRecord'>, ProblemDetail>] extends [never] ? true : false = true;
    const field: [Unknown<ApiSchema<'FieldErrorItem'>, FieldErrorItem>] extends [never] ? true : false = true;

    expect(problem && field).toBe(true);
  });

  it('keeps the form and list field models in step with form-meta and query-meta (plan 10/10, item 5.2)', () => {
    // A flag or parameter added to a field on the server fails typecheck here until the web model names it.
    const form: [Unknown<ApiSchema<'FormFieldMeta'>, FormFieldMeta>] extends [never] ? true : false = true;
    const list: [Unknown<ApiSchema<'FieldMeta'>, QueryFieldMeta>] extends [never] ? true : false = true;

    expect(form && list).toBe(true);
  });
});
