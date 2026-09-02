import { AxeBuilder } from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

export async function expectNoSeriousAccessibilityViolations(
  page: Page,
  include?: string,
): Promise<void> {
  const builder = new AxeBuilder({ page });
  if (include) builder.include(include);
  const result = await builder.analyze();
  const violations = result.violations
    .filter(violation => violation.impact === 'critical' || violation.impact === 'serious')
    .map(violation => ({
      id: violation.id,
      impact: violation.impact,
      help: violation.help,
      targets: violation.nodes.flatMap(node => node.target),
      summaries: violation.nodes.map(node => node.failureSummary),
    }));

  expect(violations, 'critical/serious axe accessibility violations').toEqual([]);
}
