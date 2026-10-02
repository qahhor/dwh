import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';
import { NOTES_FORM_META, formField, withCustomField } from '@testing/form-meta';
import { HistoryEntry, UiRecordHistoryComponent } from './ui-record-history.component';

const update: HistoryEntry = {
  id: 2,
  event: 'U',
  changedAt: '2026-09-20T10:00:00Z',
  changedBy: 7,
  changedByName: 'Анна',
  changedByLogin: 'anna',
  isApi: false,
  changes: [{ field: 'title', labelKey: 'task.title', oldValue: 'Было', newValue: 'Стало' }],
};
const creation: HistoryEntry = {
  id: 1,
  event: 'I',
  changedAt: '2026-09-19T10:00:00Z',
  changedBy: null,
  changedByName: null,
  changedByLogin: null,
  isApi: true,
  changes: [
    { field: 'priority', newValue: 'high' },
    { field: 'closed', newValue: false },
  ],
};
const page = (items: HistoryEntry[], nextCursor: string | null = null): KeysetPage<HistoryEntry> => ({
  items,
  nextCursor,
  hasMore: nextCursor !== null,
  totalReturned: items.length,
});

async function render(get: ReturnType<typeof vi.fn>, recordId: number = 42) {
  await TestBed.configureTestingModule({
    imports: [UiRecordHistoryComponent],
    providers: [{ provide: ApiService, useValue: { get } }],
  }).compileComponents();
  const fixture = TestBed.createComponent(UiRecordHistoryComponent);
  fixture.componentRef.setInput('kind', 'tasks');
  fixture.componentRef.setInput('recordId', recordId);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const toggle = host.querySelector('[data-testid="record-history-toggle"]') as HTMLButtonElement;
  return { fixture, host, toggle };
}

describe('ui-record-history', () => {
  // Plan 10/10, item 5.0: every field is named, a custom one by its own name; with the form, values read in words.
  it('names a custom field by its label and shows values as the entity card does', async () => {
    const entry: HistoryEntry = {
      ...update,
      changes: [
        { field: 'color', labelKey: 'notes.col.color', oldValue: 'blue', newValue: 'red' },
        { field: 'cfRegion', label: 'Регион', oldValue: 'Tashkent', newValue: 'Bukhara' },
        { field: 'cfOwner', label: 'Ответственный', oldValue: null, newValue: 42 },
      ],
    };
    const get = vi.fn((path: string) =>
      path.startsWith('/history') ? of(page([entry])) : of({ id: 42, name: 'Анна' }),
    );
    const { fixture, host, toggle } = await render(get);
    fixture.componentRef.setInput(
      'meta',
      withCustomField(
        NOTES_FORM_META,
        formField('cfOwner', 'ref', {
          labelKey: '',
          label: 'Ответственный',
          attribute: 'owner',
          ref: { path: '/entities/md.users', labelField: 'name', keyField: 'id', paged: true },
        }),
      ),
    );

    toggle.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const labels = [...host.querySelectorAll('dt')].map((dt) => dt.textContent);
    expect(labels).toEqual(['Цвет', 'Регион', 'Ответственный']);
    const values = [...host.querySelectorAll('dd')].map((dd) => dd.textContent?.replace(/\s+/g, ' ').trim());
    expect(values[0]).toContain('Синий');
    expect(values[0]).toContain('Красный');
    expect(values[1]).toContain('Bukhara');
    expect(values[2]).toContain('Анна');
  });

  it('loads only when opened and shows who changed which field from what to what', async () => {
    const get = vi.fn(() => of(page([update, creation])));
    const { fixture, host, toggle } = await render(get);

    expect(get).not.toHaveBeenCalled();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');

    toggle.click();
    fixture.detectChanges();

    expect(get).toHaveBeenCalledWith('/history/tasks/42', { limit: 20 }, { notifyError: false });
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const entries = host.querySelectorAll('.record-history__entry');
    expect(entries).toHaveLength(2);
    expect(entries[0].textContent).toContain('Изменение');
    expect(entries[0].textContent).toContain('Анна (@anna)');
    expect(entries[0].querySelector('dt')?.textContent).toBe('Название задачи');
    expect(entries[0].querySelector('.record-history__old')?.textContent).toBe('Было');
    expect(entries[0].querySelector('dd')?.textContent).toContain('Стало');
    // Creation by the system through the API: raw field names without a label, flags as yes/no.
    expect(entries[1].textContent).toContain('Система');
    expect(entries[1].textContent).toContain('через API');
    expect([...entries[1].querySelectorAll('dt')].map((dt) => dt.textContent)).toEqual(['priority', 'closed']);
    expect(entries[1].querySelectorAll('dd')[1].textContent?.trim()).toBe('Нет');
  });

  it('pages with the cursor and says when there are no changes', async () => {
    const get = vi
      .fn()
      .mockReturnValueOnce(of(page([update], 'c2')))
      .mockReturnValueOnce(of(page([creation])));
    const { fixture, host, toggle } = await render(get);
    toggle.click();
    fixture.detectChanges();

    (host.querySelector('[data-testid="record-history-more"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(get).toHaveBeenLastCalledWith('/history/tasks/42', { limit: 20, cursor: 'c2' }, { notifyError: false });
    expect(host.querySelectorAll('.record-history__entry')).toHaveLength(2);
    expect(host.querySelector('[data-testid="record-history-more"]')).toBeNull();

    TestBed.resetTestingModule();
    const empty = await render(vi.fn(() => of(page([]))));
    empty.toggle.click();
    empty.fixture.detectChanges();
    expect(empty.host.querySelector('[data-testid="record-history-empty"]')?.textContent).toContain(
      'Изменений пока нет',
    );
  });

  it('shows a failure with a retry and starts over for another record', async () => {
    const later = new Subject<KeysetPage<HistoryEntry>>();
    const get = vi
      .fn()
      .mockReturnValueOnce(throwError(() => ({ status: 403 })))
      .mockReturnValueOnce(of(page([update])))
      .mockReturnValueOnce(later);
    const { fixture, host, toggle } = await render(get);
    toggle.click();
    fixture.detectChanges();

    const error = host.querySelector('[data-testid="record-history-error"]') as HTMLElement;
    expect(error.getAttribute('role')).toBe('alert');
    (error.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host.querySelectorAll('.record-history__entry')).toHaveLength(1);

    fixture.componentRef.setInput('recordId', 43);
    fixture.detectChanges();
    expect(get).toHaveBeenLastCalledWith('/history/tasks/43', { limit: 20 }, { notifyError: false });
    expect(host.querySelectorAll('.record-history__entry')).toHaveLength(0);
    later.next(page([creation]));
    fixture.detectChanges();
    expect(host.querySelectorAll('.record-history__entry')).toHaveLength(1);
  });
});
