import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { FormMeta, FormValues } from '../../core/models/form-meta.models';
import { ApiService } from '../../core/services/api.service';
import { NOTES_FORM_META, formField, withCustomField } from '../../../testing/form-meta';
import { translateTest } from '../../../testing/i18n-test.stub';
import { SMTEntityCardComponent } from './smt-entity-card.component';

describe('SMTEntityCardComponent', () => {
  async function render(meta: FormMeta, value: FormValues, sections: string[] = []) {
    const api = { get: vi.fn(() => of({ id: 42, name: 'Анна Смирнова' })) };
    await TestBed.configureTestingModule({ imports: [SMTEntityCardComponent], providers: [{ provide: ApiService, useValue: api }] }).compileComponents();
    const fixture = TestBed.createComponent(SMTEntityCardComponent);
    fixture.componentRef.setInput('meta', meta);
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('sections', sections);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const lines = () => Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.entity-card-line'))
      .map(line => `${line.querySelector('dt')?.textContent?.trim()}=${line.querySelector('dd')?.textContent?.trim()}`);
    return { fixture, api, lines };
  }

  it('reads the filled fields in words and leaves the empty ones out', async () => {
    const { lines } = await render(NOTES_FORM_META, { title: 'Заметка', contentMd: '', color: 'blue', isPinned: true }, ['main', 'settings']);

    expect(lines()).toEqual([
      `${translateTest('notes.col.title')}=Заметка`,
      `${translateTest('notes.col.color')}=${translateTest('notes.color_blue')}`,
      `${translateTest('notes.col.pinned')}=${translateTest('common.yes')}`,
    ]);
  });

  it('names a referenced record by its own read', async () => {
    const owner = formField('cfOwner', 'ref', {
      labelKey: '', label: 'Ответственный', attribute: 'owner',
      ref: { path: '/iam/users', labelField: 'name', keyField: 'id', paged: true },
    });
    const { lines, api } = await render(withCustomField(NOTES_FORM_META, owner), { cfOwner: 42 }, ['custom']);

    expect(api.get).toHaveBeenCalledWith('/iam/users/42', undefined, { notifyError: false });
    expect(lines()).toEqual(['Ответственный=Анна Смирнова']);
  });

  it('draws nothing for a record with no filled field in its sections', async () => {
    const { fixture } = await render(NOTES_FORM_META, { title: 'x' }, ['settings']);

    expect((fixture.nativeElement as HTMLElement).querySelector('dl')).toBeNull();
  });
});
