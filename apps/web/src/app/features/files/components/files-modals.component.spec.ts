import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { TaskFile } from '@core/models/task.models';
import { ToastService } from '@core/services/toast.service';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { inScreen } from '@testing/in-screen';
import { FilesModalsComponent } from './files-modals.component';

const BATCH: TaskFile[] = [
  { fileId: 'f-1', fileName: 'report.pdf', sizeBytes: 1536, mimeType: 'application/pdf', createdAt: '2026-09-01' },
  { fileId: 'f-2', fileName: 'photo.png', sizeBytes: 2048, mimeType: 'image/png', createdAt: '2026-09-01' },
];

@Component({
  imports: [FilesModalsComponent],
  template: `
    <app-files-modals
      [isUploadModalOpen]="open()"
      [uploadedBatch]="batch()"
      (closeUpload)="closes = closes + 1"
      (batchFileRemoved)="removed.push($event.fileId)"
    />
  `,
})
class Host {
  readonly open = signal(true);
  readonly batch = signal<TaskFile[]>(BATCH);
  closes = 0;
  removed: string[] = [];
}

async function render(open = true) {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: ToastService, useValue: { error: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.open.set(open);
  document.body.appendChild(fixture.nativeElement);
  const settle = async () => {
    tickInZone();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  return { fixture, host: fixture.componentInstance, settle, screen: inScreen(fixture.nativeElement) };
}

describe('FilesModalsComponent', () => {
  it('opens the upload as a named dialog only when asked', async () => {
    const { host, screen, settle } = await render(false);
    expect(screen.querySelector('[role="dialog"]')).toBeNull();

    host.open.set(true);
    await settle();

    expect(screen.querySelector('[role="dialog"] .smt-modal__title')?.textContent).toBe('Загрузка файлов в хранилище');
  });

  it('lists the files uploaded in this batch and passes a removal on', async () => {
    const { host, screen, settle } = await render();

    const cards = screen.querySelectorAll('[role="dialog"] smt-file-card') as HTMLElement[];
    expect(cards.map((card) => card.textContent)).toEqual([
      expect.stringContaining('report.pdf'),
      expect.stringContaining('photo.png'),
    ]);
    (cards[1].querySelector('.smt-file-card__action--danger') as HTMLButtonElement).click();
    await settle();

    expect(host.removed).toEqual(['f-2']);
  });

  it('asks to close from its close button', async () => {
    const { host, screen, settle } = await render();

    (screen.querySelector('[role="dialog"] [data-testid="form-submit"]') as HTMLButtonElement).click();
    await settle();

    expect(host.closes).toBe(1);
  });
});
