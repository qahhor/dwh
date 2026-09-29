import { describe, expect, it } from 'vitest';

import type { FieldErrorItem, ProblemDetail } from '../models/common.models';
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
});
