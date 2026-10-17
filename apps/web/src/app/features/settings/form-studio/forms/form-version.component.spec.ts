import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { firstValueFrom, isObservable, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { FormVersionComponent } from './form-version.component';

const labels = { ru: 'Поле', uz: 'Maydon', en: 'Field' };

function detail() {
  return {
    form: { entityCode: 'studio.visits', status: 'active', activeVersion: 1, versions: 1, revision: 1 },
    declaration: {
      formatVersion: 1,
      kind: 'simple-form',
      module: { code: 'studio', title: labels },
      entity: { name: 'visits', title: { ru: 'Визиты', uz: 'Tashriflar', en: 'Visits' } },
      fields: [
        { id: 'address', type: 'text', labels, required: false, listed: true, maxLength: 100 },
        { id: 'note', type: 'textarea', labels, required: false, listed: false, maxLength: 500 },
      ],
    },
    checksum: 'a'.repeat(64),
    versions: [{ version: 1, checksum: 'a'.repeat(64) }],
  };
}

describe('FormVersionComponent', () => {
  afterEach(() => vi.restoreAllMocks());

  async function render() {
    const api = { get: vi.fn(() => of(detail())), post: vi.fn() };
    const modal = { confirm: vi.fn(() => of(true)) };
    await TestBed.configureTestingModule({
      imports: [FormVersionComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: SMTModalService, useValue: modal },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ code: 'studio.visits' }) } },
        },
      ],
    }).compileComponents();
    TestBed.inject(PermissionService).setPermissions(['studio.forms.publish']);
    const fixture = TestBed.createComponent(FormVersionComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, root: fixture.nativeElement as HTMLElement, api };
  }

  it('locks the key and type of stored fields and offers hiding instead of removal', async () => {
    const { root, component, fixture } = await render();
    expect(component.code()).toBe('studio.visits');
    const first = root.querySelector('[data-testid="new-field-0"]')!;
    expect(first.querySelector('input')?.disabled).toBe(true);
    expect(root.querySelector('[data-testid="version-hide-address"]')).not.toBeNull();
    expect(first.textContent).toContain('Ключ сохранённого поля не меняется');
    (root.querySelector('[data-testid="version-hide-note"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(component.store.draft()?.fields[1].hidden).toBe(true);
    expect(component.preview()?.fields.map((field) => field.key)).not.toContain('note');
  });

  it('adds a new field that can be removed and warns about a required field without a default', async () => {
    const { component, fixture, root } = await render();
    component.addField();
    fixture.detectChanges();
    const index = component.store.draft()!.fields.length - 1;
    component.editField(index, { ...component.store.draft()!.fields[index], required: true });
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="form-version-changes"]')?.textContent).toContain('field1');
    component.removeField(index);
    component.removeField(0);
    expect(component.store.draft()!.fields.map((field) => field.id)).toEqual(['address', 'note']);
  });

  it('edits the title and the menu item and asks before leaving a changed draft', async () => {
    const { component } = await render();
    component.setTitle('ru', 'Визиты клиентов');
    component.setIcon('event');
    component.setIcon('rocket');
    component.setSection('administration');
    component.setSection('iam');
    component.menuOrder(7);
    component.menuOrder(1.5);
    const draft = component.store.draft()!;
    expect(draft.entity.title.ru).toBe('Визиты клиентов');
    expect(draft.entity.menu).toEqual({ icon: 'event', section: 'administration', order: 7 });
    expect(component.changeText({ field: 'note', change: 'type' })).toContain('note');
    const answer = component.canLeaveRecordPage();
    expect(isObservable(answer)).toBe(true);
    if (isObservable(answer)) expect(await firstValueFrom(answer)).toBe(true);
  });
});
