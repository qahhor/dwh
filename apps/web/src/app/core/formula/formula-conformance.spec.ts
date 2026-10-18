import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { evaluateFormula } from './formula-eval';
import { FORMULA_FUNCTIONS } from './formula-functions';
import { formulaText } from './formula-values';
import { FormulaFailure, type FormulaNode, type FormulaScope } from './formula.models';

/**
 * The shared conformance cases of the formula language (ADR-0042, `docs/api/formula-conformance.json`): the server
 * checks every formula and stores its checked tree; the web interprets the same tree with the same values and scope
 * and must give the same result or failure. A refused formula has no tree and is the server's alone.
 */
interface Case {
  formula: string;
  values?: Record<string, unknown>;
  result?: { type: string; value: string | null };
  failure?: string;
  error?: unknown;
  ast?: FormulaNode;
}

const document = JSON.parse(readFileSync('../../docs/api/formula-conformance.json', 'utf8')) as {
  scope: { zone: string; now: string };
  cases: Case[];
};
const scope: FormulaScope = { zone: document.scope.zone, now: Date.parse(document.scope.now) };
const accepted = document.cases.filter((item) => item.ast);

describe('formula conformance with the server', () => {
  it('has a tree for every accepted case', () => {
    expect(accepted.length).toBeGreaterThan(150);
    expect(document.cases.filter((item) => !item.ast && !item.error)).toEqual([]);
  });

  it.each(accepted.map((item) => [item.formula, item] as const))('%s', (_formula, item) => {
    const values = item.values ?? {};
    const run = () => evaluateFormula(item.ast as FormulaNode, (key) => values[key], scope);
    if (item.failure) {
      expect(run).toThrowError(FormulaFailure);
      try {
        run();
      } catch (failure) {
        expect((failure as FormulaFailure).reason).toBe(item.failure);
      }
      return;
    }
    const value = run();
    expect(item.ast?.type).toBe(item.result?.type);
    expect(value === null ? null : formulaText(value)).toBe(item.result?.value ?? null);
  });

  it('lists the functions the server knows', () => {
    const called = new Set<string>();
    const visit = (node: FormulaNode | undefined): void => {
      if (!node) return;
      if (node.kind === 'call' && node.name) called.add(node.name);
      (node.args ?? []).forEach(visit);
    };
    accepted.forEach((item) => visit(item.ast));
    expect([...called].sort()).toEqual([...FORMULA_FUNCTIONS].sort());
  });
});
