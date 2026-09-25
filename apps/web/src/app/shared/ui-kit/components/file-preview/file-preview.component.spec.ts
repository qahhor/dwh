// @vitest-environment jsdom
import '@angular/compiler';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { tickInZone } from '../../testing/zone-tick';
import { SMTFileCardComponent } from './file-card.component';
import { canPreview, fileKind, fileKindIcon, formatFileSize } from './file-kind';
import { SMTFilePreviewService, type SMTPreviewFile } from './file-preview.component';

@Component({
  standalone: true,
  imports: [SMTFileCardComponent],
  template: `
    <smt-file-card name="photo.png" mimeType="image/png" [size]="1536" smtRemovable (download)="log.push('download')" (preview)="log.push('preview')" (remove)="log.push('remove')" />
    <smt-file-card name="report.pdf" mimeType="application/pdf" [size]="3 * 1024 * 1024" />
  `,
})
class CardHost {
  readonly log: string[] = [];
}

describe('file kinds', () => {
  it('tells the kind by media type or name and picks its icon', () => {
    expect(fileKind('image/png', 'a')).toBe('image');
    expect(fileKind(null, 'Scan.JPG')).toBe('image');
    expect(fileKind('application/pdf', 'x')).toBe('pdf');
    expect(fileKind('', 'plan.xlsx')).toBe('sheet');
    expect(fileKind('', 'notes.md')).toBe('doc');
    expect(fileKind('', 'dump.7z')).toBe('archive');
    expect(fileKind('application/octet-stream', 'blob')).toBe('other');
    expect(fileKindIcon('pdf')).toBe('picture_as_pdf');
  });

  it('previews only images, by media type when there is one', () => {
    expect(canPreview('image/jpeg', 'a.jpg')).toBe(true);
    expect(canPreview('image/svg+xml', 'logo.svg')).toBe(true);
    expect(canPreview('application/pdf', 'a.pdf')).toBe(false);
    expect(canPreview('text/html', 'photo.png')).toBe(false);
    expect(canPreview('image/tiff', 'scan.tiff')).toBe(false);
    expect(canPreview(null, 'photo.webp')).toBe(true);
  });

  it('writes sizes in the person\'s language', () => {
    expect(formatFileSize(0, 'en')).toBe('0 byte');
    expect(formatFileSize(1536, 'en')).toBe('1.5 kB');
    expect(formatFileSize(3 * 1024 * 1024, 'ru')).toMatch(/^3\sМБ$/);
  });
});

describe('SMTFileCardComponent and SMTFilePreviewService', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  async function settleFor(fixture: { whenStable(): Promise<unknown>; detectChanges(): void }) {
    tickInZone();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('shows a file with every button named after it and reports what was asked', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n('en', 'en') }] });
    const fixture = TestBed.createComponent(CardHost);
    await settleFor(fixture);
    const [photo, report] = Array.from(fixture.nativeElement.querySelectorAll('smt-file-card')) as HTMLElement[];
    const labels = (card: HTMLElement) => Array.from(card.querySelectorAll('button')).map(button => button.getAttribute('aria-label'));
    expect(labels(photo)).toEqual(['Download photo.png', 'Preview photo.png', 'Download photo.png', 'Remove photo.png']);
    expect(labels(report)).toEqual(['Download report.pdf', 'Download report.pdf']);
    expect(photo.querySelector('.smt-file-card__size')!.textContent).toBe('1.5 kB');
    expect(photo.querySelector('.smt-file-card__icon--image')).not.toBeNull();
    for (const button of Array.from(photo.querySelectorAll('button'))) button.click();
    expect(fixture.componentInstance.log).toEqual(['download', 'preview', 'download', 'remove']);
  });

  it('previews only the images, from the chosen one, and steps with buttons and arrows', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const download = vi.fn();
    const files: SMTPreviewFile[] = [
      { name: 'a.png', url: '/f/a', mimeType: 'image/png' },
      { name: 'b.pdf', url: '/f/b', mimeType: 'application/pdf' },
      { name: 'c.jpg', url: '/f/c', mimeType: 'image/jpeg' },
    ];
    const fixture = TestBed.createComponent(CardHost);
    TestBed.inject(SMTFilePreviewService).open(files, files[2], download);
    await settleFor(fixture);

    const dialog = document.querySelector('[role="dialog"]') as HTMLElement;
    const title = dialog.querySelector('h2')!;
    expect(dialog.getAttribute('aria-labelledby')).toBe(title.id);
    expect(title.textContent).toBe('c.jpg');
    expect(dialog.querySelector('img')!.getAttribute('src')).toBe('/f/c');
    expect(dialog.querySelector('.smt-file-preview__count')!.textContent).toBe('2 of 2');

    dialog.querySelector('smt-file-preview')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await settleFor(fixture);
    expect(title.textContent).toBe('a.png');
    expect((dialog.querySelector('button[aria-label="Previous"]') as HTMLButtonElement).disabled).toBe(true);

    (dialog.querySelector('button[aria-label="Download a.png"]') as HTMLButtonElement).click();
    expect(download).toHaveBeenCalledWith(files[0]);
  });

  it('says so and offers the download when an image cannot be read, and opens nothing for a non-image', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const download = vi.fn();
    const files: SMTPreviewFile[] = [{ name: 'broken.png', url: '/f/x', mimeType: 'image/png' }, { name: 'b.pdf', url: '/f/b', mimeType: 'application/pdf' }];
    const fixture = TestBed.createComponent(CardHost);
    const service = TestBed.inject(SMTFilePreviewService);
    service.open(files, files[1], download);
    await settleFor(fixture);
    expect(document.querySelector('[role="dialog"]')).toBeNull();

    service.open(files, files[0], download);
    await settleFor(fixture);
    document.querySelector('.smt-file-preview__image')!.dispatchEvent(new Event('error'));
    await settleFor(fixture);
    const alert = document.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain('This image cannot be shown.');
    (alert.querySelector('button') as HTMLButtonElement).click();
    expect(download).toHaveBeenCalledWith(files[0]);
  });
});
