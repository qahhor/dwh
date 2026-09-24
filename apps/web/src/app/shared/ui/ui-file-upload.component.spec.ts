import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TaskFile } from '../../core/models/task.models';
import { ToastService } from '../../core/services/toast.service';
import { UiFileUploadComponent } from './ui-file-upload.component';

describe('UiFileUploadComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  async function createFixture() {
    const toast = { success: vi.fn(), error: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [UiFileUploadComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: ToastService, useValue: toast }]
    }).compileComponents();
    const fixture = TestBed.createComponent(UiFileUploadComponent);
    const attached: TaskFile[] = [];
    fixture.componentInstance.fileAttached.subscribe(file => attached.push(file));
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const pick = (...names: string[]) => {
      const input = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
      Object.defineProperty(input, 'files', { value: names.map(name => new File(['x'], name)), configurable: true });
      input.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    };
    const queueNames = () =>
      Array.from(fixture.nativeElement.querySelectorAll('.queue-name')).map(node => (node as HTMLElement).textContent?.trim());
    return { fixture, toast, attached, http, pick, queueNames };
  }

  it('uses a native labelled file control inside the shared dropzone', async () => {
    const { fixture } = await createFixture();

    const input = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
    const label = fixture.nativeElement.querySelector(`label.smt-dropzone[for="${input.id}"]`) as HTMLLabelElement;

    expect(input.id).not.toBe('');
    expect(input.multiple).toBe(true);
    expect(label.textContent).toContain('Перетащите файлы');
  });

  it('uploads one file at a time and attaches each as it finishes', async () => {
    const { fixture, http, pick, queueNames, attached } = await createFixture();

    pick('a.pdf', 'b.pdf');
    const first = http.expectOne('/api/v1/files/upload');
    http.expectNone('/api/v1/files/upload');
    expect(queueNames()).toEqual(['a.pdf', 'b.pdf']);
    expect(fixture.nativeElement.textContent).toContain('В очереди');

    first.flush({ id: 'f-1', originalName: 'a.pdf', sizeBytes: 1, mimeType: 'application/pdf' });
    fixture.detectChanges();
    expect(attached.map(file => file.fileId)).toEqual(['f-1']);
    expect(queueNames()).toEqual(['b.pdf']);

    http.expectOne('/api/v1/files/upload').flush({ id: 'f-2', originalName: 'b.pdf' });
    fixture.detectChanges();
    expect(attached.map(file => file.fileId)).toEqual(['f-1', 'f-2']);
    expect(queueNames()).toEqual([]);
  });

  it('shows the running file’s own progress with progressbar semantics', async () => {
    const { fixture, http, pick } = await createFixture();

    pick('report.pdf');
    const request = http.expectOne('/api/v1/files/upload');
    request.event({ type: 1, loaded: 35, total: 100 });
    fixture.detectChanges();

    const progress = fixture.nativeElement.querySelector('[role="progressbar"]') as HTMLElement;
    expect(progress.getAttribute('aria-valuenow')).toBe('35');
    expect(progress.getAttribute('aria-label')).toContain('35%');
    request.flush({ id: 'f-1' });
  });

  it('keeps a failed file with its server message, retries it, and moves on to the next', async () => {
    const { fixture, http, pick, toast, queueNames } = await createFixture();

    pick('big.pdf', 'small.pdf');
    http.expectOne('/api/v1/files/upload').flush(
      { detail: 'Размер файла превышает допустимые 50 МБ' },
      { status: 413, statusText: 'Payload Too Large' }
    );
    fixture.detectChanges();
    expect(toast.error).toHaveBeenCalledWith('Размер файла превышает допустимые 50 МБ', 'Загрузка не удалась');
    expect(fixture.nativeElement.querySelector('.queue-error')?.textContent).toContain('50 МБ');

    // The next file starts while the failed one waits for the user.
    http.expectOne('/api/v1/files/upload').flush({ id: 'f-2' });
    fixture.detectChanges();
    expect(queueNames()).toEqual(['big.pdf']);

    (fixture.nativeElement.querySelector('button[aria-label="Повторить загрузку big.pdf"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne('/api/v1/files/upload').flush({ id: 'f-1' });
    fixture.detectChanges();
    expect(queueNames()).toEqual([]);
  });

  it('cancels a running upload and starts the next', async () => {
    const { fixture, http, pick, queueNames, attached } = await createFixture();

    pick('a.pdf', 'b.pdf');
    const first = http.expectOne('/api/v1/files/upload');
    (fixture.nativeElement.querySelector('button[aria-label="Отменить загрузку a.pdf"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(first.cancelled).toBe(true);
    expect(queueNames()).toEqual(['b.pdf']);
    http.expectOne('/api/v1/files/upload').flush({ id: 'f-2' });
    expect(attached.map(file => file.fileId)).toEqual(['f-2']);
  });

  it('names every attached file action', async () => {
    const { fixture } = await createFixture();
    fixture.componentRef.setInput('files', [{
      fileId: 'file-1',
      fileName: 'report.pdf',
      sizeBytes: 2048,
      mimeType: 'application/pdf',
      createdAt: '2026-08-30T00:00:00Z'
    }]);
    fixture.detectChanges();

    const download = fixture.nativeElement.querySelector('button[aria-label="Скачать report.pdf"]') as HTMLButtonElement;
    const remove = fixture.nativeElement.querySelector('button[aria-label="Удалить report.pdf"]') as HTMLButtonElement;
    const fileNameAction = fixture.nativeElement.querySelector('.file-info') as HTMLElement;

    expect(download.type).toBe('button');
    expect(remove.type).toBe('button');
    expect(fileNameAction.tagName).toBe('BUTTON');
  });
});
