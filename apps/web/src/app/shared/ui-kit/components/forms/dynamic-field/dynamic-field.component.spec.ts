// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import type { SMTLookupSource } from '../data-select/lookup-source';
import { SMTDynamicFieldComponent, SMTDynamicFieldDef } from './dynamic-field.component';

const users: SMTLookupSource<{ id: number; name: string }, number> = {
  page: () => of({ items: [{ id: 4, name: 'Anna' }, { id: 5, name: 'Bekzod' }], nextCursor: null, hasMore: false }),
  key: user => user.id,
  option: user => ({ label: user.name }),
  resolve: keys => of([{ id: 4, name: 'Anna' }].filter(user => keys.includes(user.id))),
};

@Component({
  standalone: true,
  imports: [SMTDynamicFieldComponent],
  template: `
    @for (field of fields; track field.code) {
      <smt-dynamic-field [field]="field" [userSource]="users" [value]="values()[field.code] ?? null" (valueChange)="set(field.code, $event)" />
    }
  `,
})
class Host {
  readonly users = users;
  readonly fields: SMTDynamicFieldDef[] = [
    { code: 'inn', label: 'INN', type: 'string', required: true, placeholder: 'Nine digits' },
    { code: 'note', label: 'Note', type: 'text' },
    { code: 'weight', label: 'Weight', type: 'number' },
    { code: 'approved', label: 'Approved', type: 'boolean' },
    { code: 'due', label: 'Due', type: 'date' },
    { code: 'starts', label: 'Starts', type: 'time' },
    { code: 'region', label: 'Region', type: 'select', options: [{ id: 'tas', label: 'Tashkent' }, { id: 'sam', label: 'Samarkand' }] },
    { code: 'curator', label: 'Curator', type: 'user_ref' },
  ];
  readonly values = signal<Record<string, unknown>>({ approved: 'true', curator: 4, starts: '09:30' });
  set(code: string, value: unknown) {
    this.values.update(values => ({ ...values, [code]: value }));
  }
}

describe('SMTDynamicFieldComponent', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  async function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const fieldOf = (label: string) => {
      const control = Array.from(element.querySelectorAll('smt-control')).find(node => node.querySelector('label')?.textContent?.includes(label))!;
      const labelFor = control.querySelector('label')!.htmlFor;
      return { control, field: element.ownerDocument.getElementById(labelFor) as HTMLElement };
    };
    return { fixture, element, fieldOf, settle };
  }

  it('draws each type with its field, every one named by its label', async () => {
    const { fieldOf } = await render();
    expect(fieldOf('INN').field.tagName).toBe('INPUT');
    expect((fieldOf('INN').field as HTMLInputElement).placeholder).toBe('Nine digits');
    expect(fieldOf('INN').control.querySelector('.smt-control__required')).not.toBeNull();
    expect(fieldOf('Note').field.tagName).toBe('TEXTAREA');
    expect((fieldOf('Weight').field as HTMLInputElement).type).toBe('number');
    expect(fieldOf('Approved').field.getAttribute('role')).toBe('switch');
    expect(fieldOf('Approved').field.getAttribute('aria-checked')).toBe('true');
    expect(fieldOf('Due').field.tagName).toBe('INPUT');
    expect((fieldOf('Starts').field as HTMLInputElement).value).toBe('09:30');
    expect(fieldOf('Region').field.getAttribute('role')).toBe('combobox');
    expect(fieldOf('Curator').field.getAttribute('role')).toBe('combobox');
    expect(fieldOf('Curator').field.textContent).toContain('Anna');
  });

  it('writes what the person enters back as the record stores it', async () => {
    const { fixture, fieldOf, settle } = await render();
    const inn = fieldOf('INN').field as HTMLInputElement;
    inn.value = '123456789';
    inn.dispatchEvent(new Event('input'));
    const weight = fieldOf('Weight').field as HTMLInputElement;
    weight.value = '12.5';
    weight.dispatchEvent(new Event('input'));
    fieldOf('Approved').field.click();
    await settle();
    expect(fixture.componentInstance.values()).toMatchObject({ inn: '123456789', weight: 12.5, approved: false });

    weight.value = '';
    weight.dispatchEvent(new Event('input'));
    await settle();
    expect(fixture.componentInstance.values()['weight']).toBeNull();

    fieldOf('Region').field.click();
    await settle();
    (Array.from(document.querySelectorAll('[role="option"]')) as HTMLElement[]).find(option => option.textContent!.includes('Samarkand'))!.click();
    await settle();
    expect(fixture.componentInstance.values()['region']).toBe('sam');
  });
});
