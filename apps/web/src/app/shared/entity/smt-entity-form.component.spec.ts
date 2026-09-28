import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { FormMeta, FormProblems, FormValues } from '@core/models/form-meta.models';
import { ApiService } from '@core/services/api.service';
import { NOTES_FORM_META, formField, withCustomField } from '@testing/form-meta';
import { translateTest } from '@testing/i18n-test.stub';
import { SMTEntityFieldDirective, SMTEntityFormComponent } from './smt-entity-form.component';

@Component({
  imports: [SMTEntityFormComponent, SMTEntityFieldDirective],
  template: `
    <smt-entity-form [meta]="meta()" [(value)]="values" [problems]="problems()" [sections]="sections()">
      @if (replaceColor()) {
        <ng-template smtEntityField="color" let-field let-set="set">
          <button type="button" class="own-color" (click)="set('red')">{{ field.key }}</button>
        </ng-template>
      }
    </smt-entity-form>
  `,
})
class HostComponent {
  readonly meta = signal<FormMeta>(NOTES_FORM_META);
  readonly values = signal<FormValues>({ title: 'Заметка', contentMd: '', color: 'blue', isPinned: false });
  readonly problems = signal<FormProblems>({});
  readonly sections = signal<string[]>([]);
  readonly replaceColor = signal(false);
}

describe('SMTEntityFormComponent', () => {
  async function render(setup: (host: HostComponent) => void = () => {}) {
    const api = { get: vi.fn(() => of({ items: [], nextCursor: null, hasMore: false })) };
    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [{ provide: ApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    setup(fixture.componentInstance);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    return { fixture, root, host: fixture.componentInstance };
  }

  it('draws the sections in order, each field with its label and required mark', async () => {
    const { root } = await render();

    const legends = Array.from(root.querySelectorAll('legend')).map((legend) => legend.textContent?.trim());
    expect(legends).toEqual([translateTest('entity.section.main'), translateTest('entity.section.settings')]);
    const fields = Array.from(root.querySelectorAll('[data-field]')).map((field) => field.getAttribute('data-field'));
    expect(fields).toEqual(['title', 'contentMd', 'color', 'isPinned']);
    const title = root.querySelector('[data-field="title"] input') as HTMLInputElement;
    expect(title.required).toBe(true);
    expect(title.value).toBe('Заметка');
    expect(title.maxLength).toBe(255);
    expect(root.querySelector('[data-field="contentMd"] ui-markdown-editor')).not.toBeNull();
    expect(root.querySelector('[data-field="isPinned"] [role="switch"]')).not.toBeNull();
  });

  it('writes what is typed into the value, by field key', async () => {
    const { fixture, root, host } = await render();

    const title = root.querySelector('[data-field="title"] input') as HTMLInputElement;
    title.value = 'Новое';
    title.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(host.values()).toEqual({ title: 'Новое', contentMd: '', color: 'blue', isPinned: false });
  });

  it('shows each problem under its field', async () => {
    const { root } = await render((host) => host.problems.set({ title: 'Слишком длинно' }));

    expect(root.querySelector('[data-field="title"]')?.textContent).toContain('Слишком длинно');
    expect(
      root.querySelector(
        '[data-field="title"] .smt-control--invalid, [data-field="title"].smt-control--invalid, [data-field="title"] smt-control.smt-control--invalid',
      ),
    ).not.toBeNull();
  });

  it('adds custom fields in their own section, named by their own label', async () => {
    const { root } = await render((host) => {
      host.meta.set(
        withCustomField(
          NOTES_FORM_META,
          formField('cfTopic', 'text', { labelKey: '', label: 'Тема', attribute: 'topic' }),
        ),
      );
      host.sections.set(['custom']);
    });

    expect(Array.from(root.querySelectorAll('[data-field]')).map((field) => field.getAttribute('data-field'))).toEqual([
      'cfTopic',
    ]);
    expect(root.querySelector('[data-field="cfTopic"] label')?.textContent).toContain('Тема');
    // One section is not titled on screen, only for assistive technology.
    expect(root.querySelector('legend.sr-only')).not.toBeNull();
  });

  it('lets a screen replace one field and keeps the rest', async () => {
    const { fixture, root, host } = await render((host) => host.replaceColor.set(true));

    const own = root.querySelector('[data-field="color"] .own-color') as HTMLButtonElement;
    expect(own).not.toBeNull();
    own.click();
    fixture.detectChanges();

    expect(host.values()['color']).toBe('red');
    expect(root.querySelector('[data-field="title"] input')).not.toBeNull();
  });
});
