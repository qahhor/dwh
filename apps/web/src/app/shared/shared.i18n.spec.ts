import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverCodeKeys, serverLiteralKeys } from '@testing/feature-i18n';
import { QUERY_OPS } from '../core/models/query-meta.models';

/** The server declarations of entities with record actions of their own (ADR-0032 6.7). */
const ENTITY_DECLARATIONS = [
  'instance/md/service/MdUserEntity.java',
  // The transitions of the reference document are record actions too (ADR-0032 9.2).
  'instance/example/service/ExampleOrderEntity.java',
];

describe('i18n: shared UI and kit', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // A filter condition names its operation as ui.filter.op.<op>.
    expect(
      featureI18nProblems({
        dir: 'src/app/shared',
        owns: ['ui.', 'common.', 'entity.'],
        english: true,
        dynamic: [
          ...QUERY_OPS.map((op) => `ui.filter.op.${op}`),
          // Section titles of entity forms come from the server (EntityDefinition layouts).
          ...serverLiteralKeys('entity.'),
          // The record page names an action's button and its confirmation by the action's code (ADR-0032 7.4):
          // the codes are the ones the entities declare.
          ...ENTITY_DECLARATIONS.flatMap((file) => [
            ...serverCodeKeys('entity.action.', { file }),
            ...serverCodeKeys('entity.action_confirm.', { file }),
          ]),
        ],
      }),
    ).toEqual([]);
  });
});
