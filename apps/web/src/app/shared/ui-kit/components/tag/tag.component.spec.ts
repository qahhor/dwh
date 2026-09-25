// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { FormField, form } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { tickInZone } from '../../testing/zone-tick';
import { SMTControlComponent } from '../forms/control/control.component';
import { SMTTagComponent, SMTTagGroupComponent, SMTTagOption } from './tag.component';
import { SMTTagGroupValueAccessor } from './tag-group-value-accessor';

const ROLES: SMTTagOption<number>[] = [
  { value: 1, label: 'Admin', disabled: true, icon: 'lock', note: 'Protected' },
  { value: 2, label: 'Manager' },
  { value: 3, label: 'Viewer' },
];

@Component({
  standalone: true,
  imports: [SMTTagComponent],
  template: `
    <smt-tag label="Draft" smtTone="warning" />
    <smt-tag label="Cement" smtRemovable (smtRemove)="removed = removed + 1" />
  `,
})
class TagHost {
  removed = 0;
}

@Component({
  standalone: true,
  imports: [SMTTagGroupComponent, SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Roles">
      <smt-tag-group [formField]="user.roleIds" [options]="roles" />
    </smt-control>
  `,
})
class FormHost {
  readonly model = signal({ roleIds: [1, 3] as number[] });
  readonly user = form(this.model);
  readonly roles = ROLES;
}

@Component({
  standalone: true,
  imports: [SMTTagGroupComponent, SMTTagGroupValueAccessor, FormsModule],
  template: `<smt-tag-group [(ngModel)]="roleIds" name="roles" [options]="roles" smtAriaLabel="Roles" />`,
})
class NgModelHost {
  roleIds: number[] = [];
  readonly roles = ROLES;
}

describe('SMTTagComponent and SMTTagGroupComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render<T>(host: new () => T) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const tags = () => Array.from(element.querySelectorAll('.smt-tag--selectable')) as HTMLButtonElement[];
    return { fixture, element, tags, settle };
  }

  it('shows a tag in its tone and names its remove button after it', async () => {
    const { fixture, element, settle } = await render(TagHost);
    const [draft, cement] = Array.from(element.querySelectorAll('smt-tag')) as HTMLElement[];
    expect(draft.classList).toContain('smt-tag--warning');
    expect(draft.querySelector('button')).toBeNull();
    const remove = cement.querySelector('button') as HTMLButtonElement;
    expect(remove.getAttribute('aria-label')).toBe('Remove Cement');
    remove.click();
    await settle();
    expect(fixture.componentInstance.removed).toBe(1);
  });

  it('is a group of toggle buttons named by the smt-control label', async () => {
    const { element, tags } = await render(FormHost);
    const group = element.querySelector('[role="group"]') as HTMLElement;
    expect(group.getAttribute('aria-labelledby')).toBe(element.querySelector('label')!.id);
    expect(tags().map(tag => tag.getAttribute('aria-pressed'))).toEqual(['true', 'false', 'true']);
    expect(tags()[0].disabled).toBe(true);
    expect(tags()[0].title).toBe('Protected');
    expect(tags()[0].textContent).toContain('Protected');
  });

  it('adds and removes keys in the order of the options, never the locked one', async () => {
    const { fixture, tags, settle } = await render(FormHost);
    tags()[1].click();
    await settle();
    expect(fixture.componentInstance.model().roleIds).toEqual([1, 2, 3]);
    tags()[2].click();
    tags()[0].click();
    await settle();
    expect(fixture.componentInstance.model().roleIds).toEqual([1, 2]);
  });

  it('marks the field touched when focus leaves the group', async () => {
    const { fixture, tags, settle } = await render(FormHost);
    tags()[1].dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: document.body }));
    await settle();
    expect(fixture.componentInstance.user.roleIds().touched()).toBe(true);
  });

  it('works through ngModel', async () => {
    const { fixture, element, tags, settle } = await render(NgModelHost);
    expect(element.querySelector('[role="group"]')!.getAttribute('aria-label')).toBe('Roles');
    tags()[2].click();
    await settle();
    expect(fixture.componentInstance.roleIds).toEqual([3]);
  });
});
