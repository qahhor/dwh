import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { FormMeta, FormValues } from '@core/models/form-meta.models';
import { ApiService } from '@core/services/api.service';
import { NOTES_FORM_META, formField, withCustomField } from '@testing/form-meta';
import { translateTest } from '@testing/i18n-test.stub';
import { SMTEntityCardComponent } from './smt-entity-card.component';

describe('SMTEntityCardComponent', () => {
  async function render(meta: FormMeta, value: FormValues, sections: string[] = []) {
    const api = { get: vi.fn(() => of({ id: 42, name: 'Анна Смирнова' })) };
    await TestBed.configureTestingModule({
      imports: [SMTEntityCardComponent],
      providers: [{ provide: ApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(SMTEntityCardComponent);
    fixture.componentRef.setInput('meta', meta);
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('sections', sections);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const lines = () =>
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.entity-card-line')).map(
        (line) => `${line.querySelector('dt')?.textContent?.trim()}=${line.querySelector('dd')?.textContent?.trim()}`,
      );
    return { fixture, api, lines };
  }

  it('reads the filled fields in words and leaves the empty ones out', async () => {
    const { lines } = await render(
      NOTES_FORM_META,
      { title: 'Заметка', contentMd: '', color: 'blue', isPinned: true },
      ['main', 'settings'],
    );

    expect(lines()).toEqual([
      `${translateTest('notes.col.title')}=Заметка`,
      `${translateTest('notes.col.color')}=${translateTest('notes.color_blue')}`,
      `${translateTest('notes.col.pinned')}=${translateTest('common.yes')}`,
    ]);
  });

  it('names a referenced record by its own read', async () => {
    const owner = formField('cfOwner', 'ref', {
      labelKey: '',
      label: 'Ответственный',
      attribute: 'owner',
      ref: { path: '/iam/users', labelField: 'name', keyField: 'id', paged: true },
    });
    const { lines, api } = await render(withCustomField(NOTES_FORM_META, owner), { cfOwner: 42 }, ['custom']);

    expect(api.get).toHaveBeenCalledWith('/iam/users/42', undefined, { notifyError: false });
    expect(lines()).toEqual(['Ответственный=Анна Смирнова']);
  });

  // Plan 10/10, item 5.0: a reference is named by its own target, not as a person.
  it('names a reference to another list by that list, and shows a moment and a time of day', async () => {
    const project = formField('cfProject', 'ref', {
      labelKey: '',
      label: 'Проект',
      attribute: 'project',
      ref: { path: '/org/units', labelField: 'title', keyField: 'code', paged: false },
    });
    const due = formField('cfDue', 'datetime', { labelKey: '', label: 'Срок', attribute: 'due' });
    const slot = formField('cfSlot', 'time', { labelKey: '', label: 'Слот', attribute: 'slot' });
    const meta = withCustomField(withCustomField(withCustomField(NOTES_FORM_META, project), due), slot);
    const api = {
      get: vi.fn(() => of([{ code: 'hq', title: 'Головной офис' }])),
    };
    await TestBed.configureTestingModule({
      imports: [SMTEntityCardComponent],
      providers: [{ provide: ApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(SMTEntityCardComponent);
    fixture.componentRef.setInput('meta', meta);
    fixture.componentRef.setInput('value', { cfProject: 'hq', cfDue: '2026-10-01T09:30', cfSlot: '14:45' });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(api.get).toHaveBeenCalledWith('/org/units', undefined, { notifyError: false });
    expect(text).toContain('Головной офис');
    expect(text).toContain('01.10.2026 09:30');
    expect(text).toContain('14:45');
  });

  it('draws nothing for a record with no filled field in its sections', async () => {
    const { fixture } = await render(NOTES_FORM_META, { title: 'x' }, ['settings']);

    expect((fixture.nativeElement as HTMLElement).querySelector('dl')).toBeNull();
  });
});
