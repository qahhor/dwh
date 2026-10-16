import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom, of, type Observable } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { FormStudioComponent } from './form-studio.component';
import type { FormStudioPreview } from './form-studio.models';

const response: FormStudioPreview = {
  declaration: {
    formatVersion: 1,
    kind: 'form-layout',
    entityCode: 'example.products',
    sections: [
      { id: 'main', fields: [{ fieldId: 'code' }, { fieldId: 'name' }, { fieldId: 'unit' }, { fieldId: 'price' }] },
    ],
  },
  meta: {
    code: 'example.products',
    fields: [
      { key: 'code', labelKey: 'example.products.col.code', type: 'text', required: true, readonlyOnUpdate: true },
      { key: 'name', labelKey: 'example.products.col.name', type: 'text', required: true },
      { key: 'unit', labelKey: 'example.products.col.unit', type: 'select', required: true, options: ['pcs'] },
      { key: 'price', labelKey: 'example.products.col.price', type: 'money', required: false, currencies: ['UZS'] },
    ],
    layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['code', 'name', 'unit', 'price'] }],
    actions: ['create'],
    capabilities: [],
  },
};

describe('FormStudioComponent', () => {
  afterEach(() => vi.restoreAllMocks());
  async function render() {
    const api = {
      get: vi.fn((path) =>
        of(
          structuredClone(
            path.endsWith('/prototype')
              ? response
              : path.endsWith('/versions')
                ? { items: [], nextCursor: null, hasMore: false, totalReturned: 0 }
                : { draft: null, meta: response.meta },
          ),
        ),
      ),
      post: vi.fn((_path, declaration) =>
        of({ ...structuredClone(response), declaration: structuredClone(declaration) }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [FormStudioComponent],
      providers: [provideRouter([]), { provide: ApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(FormStudioComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, root: fixture.nativeElement as HTMLElement, api };
  }

  it('uses the existing renderer, isolates sample values and removes the preview when a label changes', async () => {
    const { fixture, component, root, api } = await render();
    expect(root.querySelector('smt-entity-form')).not.toBeNull();
    expect(root.querySelector('[data-testid="studio-field-name"] legend')?.textContent).toBe('Название');
    const sample = root.querySelector('[data-field="name"] input') as HTMLInputElement;
    sample.value = 'Sample';
    sample.dispatchEvent(new Event('input', { bubbles: true }));
    const label = root.querySelector('[data-testid="studio-field-name"] input') as HTMLInputElement;
    label.value = 'Product';
    label.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(root.querySelector('smt-entity-form')).toBeNull();
    expect(component.store.dirty()).toBe(true);
    expect(component.values()['name']).toBe('Sample');
    expect(api.post).not.toHaveBeenCalled();
    component.setLabel('main', 'name', 'en', 'Product');
    component.setLabel('main', 'name', 'uz', 'Mahsulot');
    component.store.validate();
    fixture.detectChanges();
    expect(root.querySelector('[data-field="name"] input')?.getAttribute('aria-label')).not.toBe('name');
    expect(root.querySelector('[data-field="name"]')?.textContent).toContain('Product');
    expect(component.values()['name']).toBe('Sample');
    expect(component.store.dirty()).toBe(true);
  });

  it('retains the focused direction button while it remains available after a move', async () => {
    const { fixture, root } = await render();
    const down = root.querySelectorAll('[data-testid="studio-field-code"] button')[1] as HTMLButtonElement;
    down.focus();
    down.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(down);
    down.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(down);
  });

  it('reorders fields and sections without dropping fields or changing their stable identifiers', async () => {
    const { component, fixture, root } = await render();
    const up = root.querySelector('[data-testid="studio-field-name"] button') as HTMLButtonElement;
    up.click();
    fixture.detectChanges();
    expect(component.store.draft()!.sections[0].fields.map((field) => field.fieldId)).toEqual([
      'name',
      'code',
      'unit',
      'price',
    ]);
    component.addSection();
    expect(component.store.draft()!.sections[1].fields.map((field) => field.fieldId)).toEqual(['price']);
    component.moveSection('section_1', -1);
    expect(component.store.draft()!.sections[0].id).toBe('section_1');
    component.moveToSection('main', 'unit', 'section_1');
    component.removeSection('main');
    expect(component.store.draft()!.sections).toHaveLength(1);
    expect(
      component.store
        .draft()!
        .sections.flatMap((section) => section.fields.map((field) => field.fieldId))
        .sort(),
    ).toEqual(['code', 'name', 'price', 'unit']);
  });

  it('asks before leaving a validated but unsaved declaration and keeps dirty when download cannot start', async () => {
    const { component } = await render();
    component.moveField('main', 'name', -1);
    component.store.validate();
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));
    expect(await firstValueFrom(component.canLeaveRecordPage() as Observable<boolean>)).toBe(false);
    expect(confirm).toHaveBeenCalled();
    vi.stubGlobal('URL', {
      createObjectURL: () => {
        throw new Error('download unavailable');
      },
    });
    try {
      component.exportFile();
    } finally {
      vi.unstubAllGlobals();
    }
    expect(component.store.dirty()).toBe(true);
    expect(component.store.error()).not.toBe('');
    expect(component.preview()).not.toBeNull();
  });

  it('keeps an exported but unsaved server draft until reload is explicitly confirmed', async () => {
    const { component, api } = await render();
    component.moveField('main', 'name', -1);
    component.store.validate();
    component.store.markExported();
    expect(component.store.dirty()).toBe(false);
    expect(component.lifecycle.serverDirty()).toBe(true);
    const local = structuredClone(component.store.draft());
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));
    const reads = api.get.mock.calls.length;
    component.reloadDraft();
    expect(api.get.mock.calls).toHaveLength(reads);
    expect(component.store.draft()).toEqual(local);
    expect(await firstValueFrom(component.canLeaveRecordPage() as Observable<boolean>)).toBe(false);
    confirm.mockReturnValue(of(true));
    component.reloadDraft();
    expect(component.store.draft()).toEqual(response.declaration);
    expect(component.lifecycle.serverDirty()).toBe(false);
  });

  it('rejects malformed UTF-8 before posting an imported declaration', async () => {
    const { component, fixture, api } = await render();
    const arrayBuffer = vi.fn().mockResolvedValue(new Uint8Array([0xc3, 0x28]).buffer);
    component.importFile({ target: { files: [{ size: 2, arrayBuffer }], value: 'bad.json' } } as unknown as Event);
    await fixture.whenStable();
    expect(api.post).not.toHaveBeenCalled();
    expect(component.store.error()).not.toBe('');
    expect(component.busy()).toBe(false);
  });

  it('rejects an oversized imported file without reading it or losing the current draft', async () => {
    const { component, api } = await render();
    component.moveField('main', 'name', -1);
    const current = structuredClone(component.store.draft());
    const text = vi.fn();
    component.importFile({ target: { files: [{ size: 32769, text }], value: 'file.json' } } as unknown as Event);
    expect(text).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
    expect(component.store.draft()).toEqual(current);
    expect(component.store.dirty()).toBe(true);
  });
});
