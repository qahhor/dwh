import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { FormMeta, FormProblems, FormValues } from '@core/models/form-meta.models';
import { ApiService } from '@core/services/api.service';
import { NOTES_FORM_META } from '@testing/form-meta';
import { recordName } from './page/entity-page';
import { SMTEntityFormComponent } from './smt-entity-form.component';

/** The note form laid out on two tabs of sections, as a live form of format 4 is (ADR-0041). */
const TABBED: FormMeta = {
  ...NOTES_FORM_META,
  tabs: [
    { key: 'main', labelKey: 'ui.entity_page.tab_fields', kind: 'sections', sections: ['main'] },
    { key: 'more', labelKey: '', label: 'Настройки', kind: 'sections', sections: ['settings'] },
    { key: 'history', labelKey: 'ui.entity_page.tab_history', kind: 'history' },
  ],
};

@Component({
  imports: [SMTEntityFormComponent],
  template: `<smt-entity-form
    [meta]="meta()"
    [(value)]="values"
    [problems]="problems()"
    [submitted]="submitted()"
    [sections]="sections()"
  />`,
})
class HostComponent {
  readonly meta = signal<FormMeta>(TABBED);
  readonly values = signal<FormValues>({ title: 'Заметка', contentMd: '', color: 'blue', isPinned: false });
  readonly problems = signal<FormProblems>({});
  readonly submitted = signal(false);
  readonly sections = signal<string[]>([]);
}

describe('the tabs of an entity form', () => {
  async function render(setup: (host: HostComponent) => void = () => {}) {
    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of({ items: [], hasMore: false })) } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    setup(fixture.componentInstance);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
  }

  const shown = (root: HTMLElement) =>
    Array.from(root.querySelectorAll<HTMLElement>('fieldset.entity-section'))
      .filter((section) => !section.hidden)
      .map((section) => section.dataset['section']);

  it('draws the sections of the open tab only and keeps the others in the form', async () => {
    const { fixture, root } = await render();
    const tabs = Array.from(root.querySelectorAll<HTMLButtonElement>('.entity-form-tabs [role="tab"]'));
    expect(tabs.map((tab) => tab.textContent?.trim())).toEqual([expect.any(String), 'Настройки']);
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    expect(shown(root)).toEqual(['main']);
    expect(root.querySelectorAll('fieldset.entity-section')).toHaveLength(2);
    const panel = root.querySelector('.entity-form-panel')!;
    expect(panel.getAttribute('aria-labelledby')).toBe(tabs[0].id);
    tabs[1].click();
    fixture.detectChanges();
    expect(shown(root)).toEqual(['settings']);
    expect(root.querySelector('.entity-form-panel')!.getAttribute('aria-labelledby')).toBe(tabs[1].id);
  });

  it('counts the problems of each tab and opens the first tab with problems after a save', async () => {
    const { fixture, root, host } = await render();
    host.problems.set({ color: 'Выберите цвет' });
    fixture.detectChanges();
    expect(shown(root)).toEqual(['main']);
    expect(root.querySelectorAll('.entity-form-tabs [role="tab"]')[1].textContent).toContain('1');
    host.submitted.set(true);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(shown(root)).toEqual(['settings']);
  });

  it('draws every section at once with fewer than two tabs or when the screen chooses sections', async () => {
    const single = await render((host) =>
      host.meta.set({
        ...TABBED,
        tabs: [{ key: 'all', labelKey: 'x', kind: 'sections', sections: ['main', 'settings'] }],
      }),
    );
    expect(single.root.querySelector('.entity-form-tabs')).toBeNull();
    expect(shown(single.root)).toEqual(['main', 'settings']);
    TestBed.resetTestingModule();
    const chosen = await render((host) => host.sections.set(['settings']));
    expect(chosen.root.querySelector('.entity-form-tabs')).toBeNull();
    expect(shown(chosen.root)).toEqual(['settings']);
  });
});

describe('the name of a record', () => {
  const fallback = (id: number) => 'Запись #' + id;

  it('reads the field the entity names it by, a number as the record number', () => {
    const meta: FormMeta = {
      ...NOTES_FORM_META,
      fields: [...NOTES_FORM_META.fields, { key: 'recordNumber', labelKey: 'n', type: 'number', required: false }],
      titleField: 'recordNumber',
    };
    expect(recordName(meta, { id: 90, recordNumber: 7, title: 'Заметка' }, fallback)).toBe('Запись #7');
    expect(recordName({ ...meta, titleField: 'color' }, { id: 90, color: ' blue ', title: 'Заметка' }, fallback)).toBe(
      'blue',
    );
    expect(recordName({ ...meta, titleField: 'color' }, { id: 90, color: '', title: 'Заметка' }, fallback)).toBe(
      'Заметка',
    );
    expect(recordName({ ...meta, titleField: 'gone' }, { id: 90, title: '' }, fallback)).toBe('Запись #90');
  });
});
