import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import {
  emptyStudioListEntry,
  StudioListEntryDialogComponent,
  studioListEntryField,
} from './studio-list-entry-dialog.component';
import type { StudioListEntry } from './studio-lists.store';

describe('StudioListEntryDialogComponent', () => {
  afterEach(() => vi.restoreAllMocks());

  async function render(kind: 'list' | 'item', creating = true, confirm = true) {
    await TestBed.configureTestingModule({ imports: [StudioListEntryDialogComponent] }).compileComponents();
    const modal = { confirm: vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(confirm)) };
    const fixture = TestBed.createComponent(StudioListEntryDialogComponent);
    fixture.componentRef.setInput('kind', kind);
    fixture.componentRef.setInput('creating', creating);
    fixture.componentRef.setInput('initial', { ...emptyStudioListEntry(10), code: creating ? '' : 'repair' });
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const component = fixture.componentInstance;
    const sent: StudioListEntry[] = [];
    const closed: number[] = [];
    component.submitted.subscribe((entry) => sent.push(entry));
    component.closed.subscribe(() => closed.push(1));
    return { fixture, component, sent, closed, modal };
  }

  it('sends nothing until the code and the names are valid, then trims them', async () => {
    const { fixture, component, sent } = await render('item');
    component.submit();
    fixture.detectChanges();
    expect(sent).toEqual([]);
    expect(component.summary().length).toBeGreaterThanOrEqual(3);
    component.model.set({ code: 'Bad Code', ru: 'Ремонт', uz: 'Tamir', en: 'Repair', sortOrder: 10, active: true });
    component.submit();
    expect(sent).toEqual([]);
    component.model.set({ code: 'repair', ru: ' Ремонт ', uz: 'Tamir', en: 'Repair', sortOrder: 10, active: true });
    component.submit();
    expect(sent).toEqual([
      { code: 'repair', name: { ru: 'Ремонт', uz: 'Tamir', en: 'Repair' }, sortOrder: 10, active: true },
    ]);
  });

  it('keeps the code of an existing item and switches it off', async () => {
    const { component, sent } = await render('item', false);
    component.model.set({ code: 'repair', ru: 'Ремонт', uz: 'Tamir', en: 'Repair', sortOrder: 0, active: true });
    component.submit();
    expect(sent).toEqual([]);
    component.model.update((model) => ({ ...model, sortOrder: 5 }));
    component.setActive(false);
    component.submit();
    expect(sent[0]).toMatchObject({ code: 'repair', sortOrder: 5, active: false });
    expect(component.title()).toBe('studio.lists.edit_item');
  });

  it('asks before dropping a changed form and closes an unchanged one at once', async () => {
    const { component, closed, modal } = await render('list');
    component.close();
    expect(closed).toEqual([1]);
    component.model.update((model) => ({ ...model, ru: 'Типы' }));
    component.close();
    expect(modal.confirm).toHaveBeenCalled();
    expect(closed).toEqual([1, 1]);
    expect(component.title()).toBe('studio.lists.new_list');
    expect(studioListEntryField('name.uz')).toBe('uz');
    expect(studioListEntryField('code')).toBe('code');
  });
});
