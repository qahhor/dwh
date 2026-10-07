import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { KeysetPage } from '@core/models/common.models';
import { QueryMetaService } from '@core/services/query-meta.service';
import { entityRecord, metaField, queryMetaFixture } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';
import { EntitiesApi, type EntityRecord } from './entities.api';
import { SMTEntityRelatedComponent } from './smt-entity-related.component';

const LIST = queryMetaFixture('test.items', [
  metaField('title', '', 'text', { label: 'Название' }),
  metaField('documentId', '', 'number', { label: 'Документ' }),
  metaField('qty', '', 'number', { label: 'Кол-во' }),
]);

/* A related list of a card (ADR-0032 9.3): the records whose reference names this one, through their own list. */
describe('SMTEntityRelatedComponent', () => {
  let page: Subject<KeysetPage<EntityRecord>>;
  const entities = { page: vi.fn(() => page.asObservable()) };

  beforeEach(() => {
    page = new Subject();
    entities.page.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EntitiesApi, useValue: entities },
        { provide: QueryMetaService, useValue: { get: () => of(LIST) } },
      ],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(SMTEntityRelatedComponent);
    fixture.componentRef.setInput('entity', 'test.items');
    fixture.componentRef.setInput('field', 'documentId');
    fixture.componentRef.setInput('recordId', 5);
    fixture.componentRef.setInput('caption', 'Позиции');
    fixture.detectChanges();
    await settle(fixture);
    return { fixture, related: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  async function settle(fixture: { detectChanges(): void }) {
    for (let round = 0; round < 3; round += 1) {
      await new Promise((resolve) => setTimeout(resolve));
      fixture.detectChanges();
    }
  }

  function answer(items: EntityRecord[]) {
    page.next({ items, nextCursor: null, hasMore: false } as unknown as KeysetPage<EntityRecord>);
    page.complete();
  }

  it('asks the related list for the records that name this one, and says it is loading meanwhile', async () => {
    const { related, host } = await render();

    expect(entities.page).toHaveBeenCalledWith(
      'test.items',
      { conditions: [{ field: 'documentId', op: 'eq', value: 5 }] },
      null,
      20,
    );
    expect(related.filterText()).toBe('[{"field":"documentId","op":"eq","value":5}]');
    expect(host.querySelector('[role="status"]')?.textContent?.trim()).toBe(translateTest('common.loading'));
  });

  it('shows the first columns without the reference itself, linking each record and the whole list', async () => {
    const { fixture, host } = await render();
    answer([entityRecord(31, { title: 'Связанная', documentId: 5, qty: 2 })]);
    await settle(fixture);

    const headers = Array.from(host.querySelectorAll('th')).map((cell) => cell.textContent?.trim());
    expect(headers).toEqual(['Название', 'Кол-во']);
    expect(host.querySelector('caption')?.textContent?.trim()).toBe('Позиции');
    const link = host.querySelector('tr[data-record="31"] a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/e/test.items/31');
    expect(link.textContent?.trim()).toBe('Связанная');

    const all = host.querySelector('.entity-related-all') as HTMLAnchorElement;
    expect(decodeURIComponent(all.getAttribute('href') ?? '')).toBe(
      '/e/test.items?filter=[{"field":"documentId","op":"eq","value":5}]',
    );
  });

  it('says there are none, or that the list could not be read', async () => {
    const empty = await render();
    answer([]);
    await settle(empty.fixture);
    expect(empty.host.textContent).toContain(translateTest('ui.entity_page.related_empty'));

    page = new Subject();
    const failed = await render();
    page.error({ status: 500 });
    await settle(failed.fixture);
    expect(failed.host.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      translateTest('ui.entity_page.related_failed'),
    );
  });
});
