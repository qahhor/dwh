import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { FileUploadApi, type UploadStep } from '../ui/file-upload.api';
import { EntitiesApi } from './entities.api';
import { SMTFileFieldComponent } from './smt-file-field.component';

describe('SMTFileFieldComponent', () => {
  let answers: Subject<UploadStep>[];
  const uploads = {
    upload: vi.fn(() => {
      const answer = new Subject<UploadStep>();
      answers.push(answer);
      return answer.asObservable();
    }),
  };
  const api = {
    fileUrl: (code: string, record: number, id: string) => `/api/v1/entities/${code}/${record}/files/${id}`,
  };

  beforeEach(() => {
    answers = [];
    uploads.upload.mockClear();
    TestBed.configureTestingModule({
      providers: [
        { provide: EntitiesApi, useValue: api },
        { provide: FileUploadApi, useValue: uploads },
      ],
    });
  });

  function render(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(SMTFileFieldComponent);
    fixture.componentRef.setInput('label', 'Договор');
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    return { fixture, field: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  function done(index: number, id: string, name: string): void {
    answers[index].next({ kind: 'done', file: { id, originalName: name, sizeBytes: 4, mimeType: 'text/plain' } });
    answers[index].complete();
  }

  it('offers the drop zone while empty, and names the accepted types', () => {
    const { field, host } = render({ contentTypes: ['application/pdf', 'image/png'] });

    expect(host.querySelector('smt-dropzone')).not.toBeNull();
    expect(field.accept()).toBe('application/pdf,image/png');
    expect(field.hint()).toBe(translateTest('ui.entity_form.file_types', { types: 'application/pdf,image/png' }));
    expect(render().field.hint()).toBe('');
  });

  it('shows the progress of an upload, then that the file is checked, and holds the stored file', () => {
    const { fixture, field, host } = render();
    const file = new File(['%PDF'], 'contract.pdf', { type: 'application/pdf' });

    field.upload([file]);
    fixture.detectChanges();
    expect(uploads.upload).toHaveBeenCalledWith(file);
    expect(host.querySelector('[role="status"]')?.textContent?.trim()).toBe(
      translateTest('ui.entity_form.file_uploading'),
    );

    answers[0].next({ kind: 'progress', percent: 40 });
    fixture.detectChanges();
    expect(host.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('40');

    answers[0].next({ kind: 'progress', percent: 100 });
    fixture.detectChanges();
    expect(field.checking()).toBe(true);
    expect(host.querySelector('[role="status"]')?.textContent?.trim()).toBe(
      translateTest('ui.entity_form.file_checking'),
    );

    done(0, 'f-1', 'contract.pdf');
    fixture.detectChanges();
    expect(field.uploading()).toBe(false);
    expect(field.value()).toEqual({ id: 'f-1', name: 'contract.pdf', size: 4, contentType: 'text/plain' });
    expect(host.querySelector('.file-name')?.textContent?.trim()).toBe('contract.pdf');
    expect(host.querySelector('[role="status"]')).toBeNull();
    expect(host.querySelector('smt-dropzone')).toBeNull();
  });

  it('ignores an empty pick', () => {
    render().field.upload([]);
    expect(uploads.upload).not.toHaveBeenCalled();
  });

  it('shows the server problem of a failed upload, or its own text', () => {
    const { field } = render();

    field.upload([new File(['x'], 'a.exe')]);
    answers[0].error({ error: { detail: 'Тип файла не разрешён' } });
    expect(field.failure()).toBe('Тип файла не разрешён');

    field.upload([new File(['x'], 'b.exe')]);
    expect(field.failure()).toBe('');
    answers[1].error({});
    expect(field.failure()).toBe(translateTest('ui.entity_form.file_failed'));
  });

  it('links a chosen file through its record once the record exists, and takes it away', () => {
    const { fixture, field, host } = render({ value: 'f-9' });
    expect(host.querySelector('a.file-name')).toBeNull();
    expect(host.querySelector('span.file-name')?.textContent?.trim()).toBe('f-9');
    expect(field.chosen()).toBe('f-9');

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

  it('holds several files up to its limit, one upload after another, and removes each by its button', () => {
    const { fixture, field, host } = render({ maxFiles: 2, value: [{ id: 'f-1', name: 'one.txt' }] });
    expect(field.several()).toBe(true);
    expect(field.chosen()).toBeNull();
    expect(host.textContent).toContain(translateTest('ui.entity_form.files_count', { count: 1, max: 2 }));

    field.upload([new File(['2'], 'two.txt'), new File(['3'], 'three.txt')]);
    expect(uploads.upload).toHaveBeenCalledTimes(1);
    done(0, 'f-2', 'two.txt');
    fixture.detectChanges();
    expect(uploads.upload).toHaveBeenCalledTimes(1);
    expect(field.value()).toEqual([
      { id: 'f-1', name: 'one.txt' },
      { id: 'f-2', name: 'two.txt', size: 4, contentType: 'text/plain' },
    ]);
    expect(host.querySelectorAll('.file-chosen')).toHaveLength(2);
    expect(host.querySelector('smt-dropzone')).toBeNull();

    (host.querySelectorAll('.file-chosen button')[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(field.value()).toEqual([{ id: 'f-2', name: 'two.txt', size: 4, contentType: 'text/plain' }]);
    expect(host.querySelector('smt-dropzone')).not.toBeNull();

    field.remove('f-2');
    expect(field.value()).toBeNull();
  });
});
