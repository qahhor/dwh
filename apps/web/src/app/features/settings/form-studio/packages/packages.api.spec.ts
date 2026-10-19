import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PackagesApi } from './packages.api';

/* The transport of solution packages (ADR-0047): paths, the suffix of a second copy and the mapping. */
describe('PackagesApi', () => {
  let http: HttpTestingController;
  let api: PackagesApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(PackagesApi);
  });

  afterEach(() => http.verify());

  it('reads the gallery and creates from a template with or without a suffix', () => {
    api.templates().subscribe();
    http.expectOne('/api/v1/studio/templates').flush([]);
    api.fromTemplate('order-approval').subscribe();
    expect(http.expectOne('/api/v1/studio/templates/order-approval/imports').request.method).toBe('POST');
    api.fromTemplate('order-approval', ' _b ').subscribe();
    http.expectOne('/api/v1/studio/templates/order-approval/imports?suffix=_b').flush({ id: 1 });
  });

  it('uploads a package, pages the history, reads and applies an import', () => {
    api.upload({ kind: 'solution-package' }, null).subscribe();
    const upload = http.expectOne('/api/v1/studio/packages/imports');
    expect(upload.request.body).toEqual({ kind: 'solution-package' });
    upload.flush({ id: 2 });
    api.imports('c2').subscribe();
    http.expectOne('/api/v1/studio/packages/imports?limit=20&cursor=c2').flush({ items: [] });
    api.imports().subscribe();
    http.expectOne('/api/v1/studio/packages/imports?limit=20').flush({ items: [] });
    api.get(2).subscribe();
    http.expectOne('/api/v1/studio/packages/imports/2').flush({});
    api.apply(2, { approver: [4] }, true).subscribe();
    const apply = http.expectOne('/api/v1/studio/packages/imports/2/apply');
    expect(apply.request.body).toEqual({ mapping: { approver: [4] }, dryRun: true });
    apply.flush({});
  });

  it('plans and downloads an export and reads the forms and roles to choose from', () => {
    const request = {
      code: 'orders',
      version: '1.0.0',
      title: { ru: 'З', uz: 'B', en: 'O' },
      forms: ['studio.orders'],
    };
    api.plan(request).subscribe();
    http.expectOne('/api/v1/studio/packages/export/preview').flush({ included: [], related: [], placeholders: [] });
    api.export(request).subscribe();
    http.expectOne('/api/v1/studio/packages/export').flush({});
    api.forms().subscribe();
    http.expectOne('/api/v1/studio/forms?limit=100').flush({ items: [] });
    api.roles().subscribe();
    http.expectOne('/api/v1/iam/roles').flush([]);
  });
});
