import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverCodeKeys, serverLiteralKeys } from '@testing/feature-i18n';
import { QUERY_OPS } from '../core/models/query-meta.models';
import { REPORT_OPS, REPORT_TRUNCS } from './entity/report/entity-reports';

/** The server declarations of entities with record actions of their own (ADR-0032 6.7). */
const ENTITY_DECLARATIONS = [
  'instance/md/service/MdUserEntity.java',
  // The transitions of the reference document are record actions too (ADR-0032 9.2).
  'instance/example/service/ExampleOrderEntity.java',
  'instance/example/service/ExampleRequestsEntity.java',
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
          // A report names its measures and date buckets as ui.report.op.<op> and ui.report.trunc.<bucket>.
          ...REPORT_OPS.map((op) => `ui.report.op.${op}`),
          ...REPORT_TRUNCS.map((trunc) => `ui.report.trunc.${trunc}`),
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
