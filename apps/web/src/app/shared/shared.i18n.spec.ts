import { describe, expect, it } from 'vitest';
import { featureI18nProblems } from '../../testing/feature-i18n';
import { QUERY_OPS } from '../core/models/query-meta.models';

describe('i18n: shared UI and kit', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // A filter condition names its operation as ui.filter.op.<op>.
    expect(featureI18nProblems({
      dir: 'src/app/shared', owns: ['ui.', 'common.'], english: true,
      dynamic: [
        ...QUERY_OPS.map(op => `ui.filter.op.${op}`),
      ],
    })).toEqual([]);
  });
});
