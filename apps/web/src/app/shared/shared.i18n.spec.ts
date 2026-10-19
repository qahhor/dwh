import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverCodeKeys, serverLiteralKeys } from '@testing/feature-i18n';
import { QUERY_OPS } from '../core/models/query-meta.models';
import { REPORT_OPS, REPORT_TRUNCS } from './entity/report/entity-reports';
import {
  COMPARE_AGAINST,
  COMPARE_PERIODS,
  PERIOD_PRESETS,
  REPORT_PERIODS,
  REPORT_SHARES,
} from './entity/report/report-analytics';

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
          // ADR-0044: shares, periods, comparisons and presets are named by their codes.
          ...REPORT_SHARES.flatMap((share) => [`ui.report.share.${share}`, `ui.report.share_of.${share}`]),
          ...REPORT_PERIODS.map((period) => `ui.report.period.${period}`),
          ...COMPARE_PERIODS.map((period) => `ui.report.compare.${period}`),
          ...COMPARE_AGAINST.map((against) => `ui.report.compare.against_${against}`),
          ...PERIOD_PRESETS.map((preset) => `ui.report.preset.${preset}`),
          // ADR-0046: the history labels the automation of a change with the key the server sends.
          'ui.history.automation',
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
