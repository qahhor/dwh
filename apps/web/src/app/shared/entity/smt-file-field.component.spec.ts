import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { FileValue } from '@core/services/field-values';
import { translateTest } from '@testing/i18n-test.stub';
import { EntitiesApi } from './entities.api';
import { SMTFileFieldComponent } from './smt-file-field.component';

describe('SMTFileFieldComponent', () => {
  let answer: Subject<FileValue>;
  const api = {
    uploadFile: vi.fn(() => answer.asObservable()),
    fileUrl: (code: string, record: number, id: string) => `/api/v1/entities/${code}/${record}/files/${id}`,
  };

  beforeEach(() => {
    answer = new Subject<FileValue>();
    api.uploadFile.mockClear();
    TestBed.configureTestingModule({ providers: [{ provide: EntitiesApi, useValue: api }] });
  });

  function render(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(SMTFileFieldComponent);
    fixture.componentRef.setInput('label', 'Договор');
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    return { fixture, field: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  it('offers the drop zone while empty, and names the accepted types', () => {
    const { field, host } = render({ contentTypes: ['application/pdf', 'image/png'] });

    expect(host.querySelector('smt-dropzone')).not.toBeNull();
    expect(field.accept()).toBe('application/pdf,image/png');
    expect(field.hint()).toBe(translateTest('ui.entity_form.file_types', { types: 'application/pdf,image/png' }));
    expect(render().field.hint()).toBe('');
  });

  it('uploads the first file, says so while it runs, and holds the stored file', () => {
    const { fixture, field, host } = render();
    const file = new File(['%PDF'], 'contract.pdf', { type: 'application/pdf' });

    field.upload([file]);
    fixture.detectChanges();
    expect(api.uploadFile).toHaveBeenCalledWith(file);
    expect(host.querySelector('[role="status"]')?.textContent?.trim()).toBe(
      translateTest('ui.entity_form.file_uploading'),
    );

    answer.next({ id: 'f-1', name: 'contract.pdf' });
    fixture.detectChanges();
    expect(field.uploading()).toBe(false);
    expect(field.value()).toEqual({ id: 'f-1', name: 'contract.pdf' });
    expect(host.querySelector('.file-name')?.textContent?.trim()).toBe('contract.pdf');
    expect(host.querySelector('[role="status"]')).toBeNull();
  });

  it('ignores an empty pick', () => {
    render().field.upload([]);
    expect(api.uploadFile).not.toHaveBeenCalled();
  });

  it('shows the server problem of a failed upload, or its own text', () => {
    const { field } = render();

    field.upload([new File(['x'], 'a.exe')]);
    answer.error({ detail: 'Тип файла не разрешён' });
    expect(field.failure()).toBe('Тип файла не разрешён');

    answer = new Subject<FileValue>();
    field.upload([new File(['x'], 'b.exe')]);
    expect(field.failure()).toBe('');
    answer.error({});
    expect(field.failure()).toBe(translateTest('ui.entity_form.file_failed'));
  });

  it('links a chosen file through its record once the record exists, and takes it away', () => {
    const { fixture, field, host } = render({ value: 'f-9' });
    expect(host.querySelector('a.file-name')).toBeNull();
    expect(host.querySelector('span.file-name')?.textContent?.trim()).toBe('f-9');

    fixture.componentRef.setInput('entity', 'demo_requests');
    fixture.componentRef.setInput('recordId', 7);
    fixture.detectChanges();
    expect(host.querySelector('a.file-name')?.getAttribute('href')).toBe('/api/v1/entities/demo_requests/7/files/f-9');

    const remove = host.querySelector('.file-chosen button') as HTMLButtonElement;
    expect(remove.getAttribute('aria-label')).toBe(translateTest('ui.entity_form.file_remove', { name: 'f-9' }));
    remove.click();
    fixture.detectChanges();
    expect(field.value()).toBeNull();
    expect(host.querySelector('smt-dropzone')).not.toBeNull();
  });
});
