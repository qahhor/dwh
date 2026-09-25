import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverCodeKeys, serverLiteralKeys } from '../../../testing/feature-i18n';
import { UPL_PACKAGE_CODES } from './packages/packages-errors';

describe('i18n: upl', () => {
  it('uses only translated keys and none of its keys is dead', () => {
    // upl.err.<code> is built from the codes the server answers with; the list columns,
    // the xlsx template and the error file are labelled by the server itself.
    expect(featureI18nProblems({
      dir: 'src/app/features/upl', owns: ['upl.'], english: false,
      dynamic: [
        ...UPL_PACKAGE_CODES.map(code => `upl.err.${code}`),
        ...serverCodeKeys('upl.err.'),
        ...serverLiteralKeys('upl.'),
      ],
    })).toEqual([]);
  });
});
