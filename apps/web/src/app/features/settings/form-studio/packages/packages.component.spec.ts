import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PermissionService } from '@core/services/permission.service';
import { translateTest } from '@testing/i18n-test.stub';
import { PackagesApi } from './packages.api';
import { PACKAGE_MAX_BYTES, PackagesComponent } from './packages.component';

const TEMPLATE = {
  code: 'order-approval',
  version: '1.0.0',
  title: { ru: 'Заказ со строками и согласованием', uz: 'Buyurtma', en: 'Order' },
  description: { ru: 'Заказ клиента', uz: 'Buyurtma', en: 'Order' },
  forms: 2,
  lists: 2,
  reports: 1,
  mailings: 1,
  automations: 1,
};
const SUMMARY = {
  id: 7,
  code: 'order-approval',
  version: '1.0.0',
  title: TEMPLATE.title,
  source: 'template' as const,
  status: 'applied' as const,
  counts: { create: 7, upgrade: 0, unchanged: 0, skip: 2, refused: 0 },
  createdAt: '2026-10-19T08:00:00Z',
};

/* Solution packages and templates (ADR-0047): the gallery, the upload of a package and the history of imports. */
describe('PackagesComponent', () => {
  const api = {
    templates: vi.fn(() => of([TEMPLATE])),
    fromTemplate: vi.fn(() => of({ id: 9 })),
    upload: vi.fn(() => of({ id: 10 })),
    imports: vi.fn(() => of({ items: [SUMMARY], hasMore: true, nextCursor: 'c2' })),
    forms: vi.fn(() => of({ items: [] })),
    plan: vi.fn(),
    export: vi.fn(),
  };
  let rights = new Set(['import', 'export']);
  let query: Record<string, string> = {};

  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockClear());
    rights = new Set(['import', 'export']);
    query = {};
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useFactory: () => ({ snapshot: { queryParamMap: convertToParamMap(query) } }) },
        { provide: PackagesApi, useValue: api },
        {
          provide: PermissionService,
          useValue: {
            hasPermission: (form: string, action: string) => form === 'studio.packages' && rights.has(action),
          },
        },
      ],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(PackagesComponent);
    fixture.detectChanges();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement, navigate };
  }

  it('shows the templates and creates a solution from one through its import', () => {
    const { host, component, navigate } = render();
    expect(component.tabs().map((tab) => tab.value)).toEqual(['templates', 'import', 'export', 'history']);
    const card = host.querySelector('[data-testid="package-template"]');
    expect(card?.textContent).toContain('Заказ со строками и согласованием');
    expect(card?.textContent).toContain(component.holds(TEMPLATE));
    (host.querySelector('[data-testid="package-template-create-order-approval"]') as HTMLButtonElement).click();
    expect(api.fromTemplate).toHaveBeenCalledWith('order-approval');
    expect(navigate).toHaveBeenCalledWith(['/settings/form-studio/packages/imports', 9]);
  });

  it('reports a template that cannot be created and a gallery that does not load', () => {
    api.fromTemplate.mockReturnValueOnce(throwError(() => ({ detail: 'Нет права' })));
    const { component, fixture, host } = render();
    component.create(TEMPLATE);
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="packages-error"]')?.textContent).toContain('Нет права');
    api.templates.mockReturnValueOnce(throwError(() => ({})));
    const again = TestBed.createComponent(PackagesComponent);
    again.detectChanges();
    expect(again.componentInstance.error()).toBe(translateTest('studio.packages.load_failed'));
  });

  it('opens the tab of the address and hides export without its right', () => {
    query = { tab: 'history' };
    rights = new Set(['import']);
    const { component, host } = render();
    expect(component.tab()).toBe('history');
    expect(component.tabs().map((tab) => tab.value)).not.toContain('export');
    expect(host.querySelector('[data-testid="package-import"]')?.textContent).toContain(component.counts(SUMMARY));
    component.loadImports('c2');
    expect(api.imports).toHaveBeenLastCalledWith('c2');
    expect(component.imports().length).toBe(2);
  });

  it('checks an uploaded file and opens its import', async () => {
    const { component, navigate } = render();
    component.choose('import');
    component.upload();
    expect(component.fileError()).toBe(translateTest('studio.packages.file_required'));
    component.pick(pickEvent(new File(['{"kind":"solution-package"}'], 'p.json')));
    component.uploadModel.set({ suffix: 'bad' });
    component.upload();
    expect(component.suffixError()).toBe(translateTest('studio.packages.suffix_invalid'));
    expect(api.upload).not.toHaveBeenCalled();
    component.uploadModel.set({ suffix: '_b' });
    component.upload();
    await vi.waitFor(() => expect(api.upload).toHaveBeenCalledWith({ kind: 'solution-package' }, '_b'));
    expect(navigate).toHaveBeenCalledWith(['/settings/form-studio/packages/imports', 10]);
  });

  it('refuses a large file, text that is no JSON and a package the server refuses', async () => {
    const { component } = render();
    const large = new File(['x'], 'big.json');
    Object.defineProperty(large, 'size', { value: PACKAGE_MAX_BYTES + 1 });
    component.pick(pickEvent(large));
    expect(component.fileError()).toBe(translateTest('studio.packages.file_too_large'));
    component.pick(pickEvent(new File(['not json'], 'p.json')));
    component.upload();
    await vi.waitFor(() => expect(component.fileError()).toBe(translateTest('studio.packages.file_not_json')));
    api.upload.mockReturnValueOnce(throwError(() => ({ detail: 'Контрольная сумма не совпадает' })));
    component.pick(pickEvent(new File(['{}'], 'p.json')));
    component.upload();
    await vi.waitFor(() => expect(component.uploadError()).toBe('Контрольная сумма не совпадает'));
  });

  it('reports a history that does not load', () => {
    api.imports.mockReturnValueOnce(throwError(() => ({})));
    const { component } = render();
    component.choose('history');
    expect(component.error()).toBe(translateTest('studio.packages.load_failed'));
  });
});

function pickEvent(file: File): Event {
  return { target: { files: [file], value: 'p.json' } } as unknown as Event;
}
