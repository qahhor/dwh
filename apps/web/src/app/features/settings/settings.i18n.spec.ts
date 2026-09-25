import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverCodeKeys } from '../../../testing/feature-i18n';
import { SEARCH_ENTITIES } from '../../core/models/search-management.models';

describe('i18n: settings', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // Search settings name each entity and each field the server's search policy indexes.
    expect(featureI18nProblems({
      dir: 'src/app/features/settings', owns: ['settings.', 'modules.'], english: true,
      dynamic: [
        ...SEARCH_ENTITIES.map(entity => `settings.search.entity.${entity.toLowerCase()}`),
        ...serverCodeKeys('settings.search.field.', { file: 'instance/search/service/SearchQueryPolicy.java' }),
      ],
    })).toEqual([]);
  });
});
