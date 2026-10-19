import { afterEach, describe, expect, it, vi } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { countsLine, downloadJson, labelOf, outcomeTone, problemLines } from './package-words';
import type { SolutionImportItem } from './packages.api';

/* The words of solution packages (ADR-0047): outcomes, names, reasons, counts and the downloaded file. */
describe('package words', () => {
  afterEach(() => vi.restoreAllMocks());

  it('gives each outcome its tone and a name in the viewer language', () => {
    expect(outcomeTone('create')).toBe('success');
    expect(outcomeTone('upgrade')).toBe('info');
    expect(outcomeTone('refused')).toBe('danger');
    expect(outcomeTone('skip')).toBe('warning');
    expect(outcomeTone('unchanged')).toBe('neutral');
    const labels = { ru: 'Заказы', uz: 'Buyurtmalar', en: '' };
    expect(labelOf(labels, 'uz')).toBe('Buyurtmalar');
    expect(labelOf(labels, 'en')).toBe('Заказы');
    expect(labelOf(undefined, 'ru')).toBe('');
  });

  it('writes the problems of a refused item with their part and values', () => {
    const item: SolutionImportItem = {
      kind: 'form',
      key: 'studio.orders',
      title: { ru: 'Заказы', uz: 'Buyurtmalar', en: 'Orders' },
      outcome: 'refused',
      notes: [],
      problems: [
        { message: 'error.studio.solution_unmapped', params: { key: 'approver' } },
        { field: 'fields[0]', message: 'error.studio.solution_record_value', params: { field: 'client', n: 2 } },
      ],
    };
    expect(problemLines(item, translateTest)).toEqual([
      translateTest('error.studio.solution_unmapped', { key: 'approver' }),
      `fields[0]: ${translateTest('error.studio.solution_record_value', { field: 'client' })}`,
    ]);
  });

  it('counts only what happens', () => {
    expect(countsLine({ create: 2, upgrade: 0, unchanged: 1, skip: 0, refused: 1 }, translateTest)).toBe(
      [
        translateTest('studio.packages.count.create', { n: 2 }),
        translateTest('studio.packages.count.unchanged', { n: 1 }),
        translateTest('studio.packages.count.refused', { n: 1 }),
      ].join(' · '),
    );
  });

  it('downloads the package as a JSON file', () => {
    const create = vi.fn(() => 'blob:package');
    const revoke = vi.fn();
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    downloadJson('orders-1.0.0.json', '{}');
    expect(create).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(revoke).toHaveBeenCalledWith('blob:package');
  });
});
