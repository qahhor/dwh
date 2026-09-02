import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ToastService } from '../../core/services/toast.service';
import { UiFileUploadComponent } from './ui-file-upload.component';

describe('UiFileUploadComponent', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [UiFileUploadComponent],
      providers: [
        provideHttpClient(),
        { provide: ToastService, useValue: { success() {}, error() {} } }
      ]
    }).compileComponents();
    return TestBed.createComponent(UiFileUploadComponent);
  }

  it('uses a native labelled file control instead of a click-only drop zone', async () => {
    const fixture = await createFixture();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
    const label = fixture.nativeElement.querySelector(`label.drop-zone[for="${input.id}"]`) as HTMLLabelElement;

    expect(input.id).not.toBe('');
    expect(input.multiple).toBe(true);
    expect(label.textContent).toContain('Перетащите файлы');
  });

  it('names every file action and exposes upload progress semantics', async () => {
    const fixture = await createFixture();
    fixture.componentRef.setInput('files', [{
      fileId: 'file-1',
      fileName: 'report.pdf',
      sizeBytes: 2048,
      mimeType: 'application/pdf',
      createdAt: '2026-08-30T00:00:00Z'
    }]);
    fixture.componentInstance.isUploading.set(true);
    fixture.componentInstance.uploadProgress.set(35);
    fixture.detectChanges();

    const progress = fixture.nativeElement.querySelector('[role="progressbar"]') as HTMLElement;
    const download = fixture.nativeElement.querySelector('button[aria-label="Скачать report.pdf"]') as HTMLButtonElement;
    const remove = fixture.nativeElement.querySelector('button[aria-label="Удалить report.pdf"]') as HTMLButtonElement;
    const fileNameAction = fixture.nativeElement.querySelector('.file-info') as HTMLElement;

    expect(progress.getAttribute('aria-valuenow')).toBe('35');
    expect(download.type).toBe('button');
    expect(remove.type).toBe('button');
    expect(fileNameAction.tagName).toBe('BUTTON');
  });
});
