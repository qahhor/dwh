import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiBulkResultComponent } from './ui-bulk-result.component';

describe('ui-bulk-result', () => {
  it('names each record that failed with its reason and closes', async () => {
    await TestBed.configureTestingModule({ imports: [UiBulkResultComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiBulkResultComponent);
    const closed: number[] = [];
    fixture.componentInstance.closed.subscribe(() => closed.push(1));
    fixture.componentRef.setInput('itemLabel', (id: number) => `#${id} Report`);
    fixture.componentRef.setInput('result', {
      action: 'status', succeeded: 2, failed: 2,
      results: [
        { id: 1, ok: true, code: null, message: null },
        { id: 7, ok: false, code: 'task_not_found', message: 'Задача не найдена' },
        { id: 9, ok: false, code: 'bulk_item_failed', message: null }
      ]
    });
    fixture.detectChanges();

    const dialog = document.querySelector('[role="dialog"]') as HTMLElement;
    expect(dialog.textContent).toContain('Изменено: 2. Не изменено: 2.');
    const failures = [...dialog.querySelectorAll('[data-testid="bulk-result-failure"]')].map(item => item.textContent?.replace(/\s+/g, ' ').trim());
    expect(failures).toEqual(['#7 Report: Задача не найдена', '#9 Report: bulk_item_failed']);

    fixture.componentRef.setInput('result', null);
    fixture.detectChanges();
    expect(document.querySelector('[data-testid="bulk-result-failure"]')).toBeNull();
  });
});
