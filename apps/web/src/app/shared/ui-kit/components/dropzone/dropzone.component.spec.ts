// @vitest-environment jsdom
import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { acceptsFile, formatFileSize, SMTDropzoneComponent, SMTDropzoneRejection } from './dropzone.component';

const file = (name: string, type = '', size = 1) => {
  const created = new File(['x'], name, { type });
  Object.defineProperty(created, 'size', { value: size });
  return created;
};

describe('dropzone helpers', () => {
  it('matches extensions, wildcard types and exact types', () => {
    expect(acceptsFile(file('a.PDF'), '.pdf')).toBe(true);
    expect(acceptsFile(file('a.png', 'image/png'), 'image/*')).toBe(true);
    expect(acceptsFile(file('a.zip', 'application/zip'), '.pdf, application/zip')).toBe(true);
    expect(acceptsFile(file('a.exe', 'application/x-msdownload'), '.pdf,image/*')).toBe(false);
    expect(acceptsFile(file('anything'), '')).toBe(true);
  });

  it('writes sizes for people', () => {
    expect(formatFileSize(20 * 1024 * 1024)).toBe('20 MB');
    expect(formatFileSize(512 * 1024)).toBe('512 KB');
    expect(formatFileSize(10)).toBe('10 B');
  });
});

describe('SMTDropzoneComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  function render(inputs: Record<string, unknown> = {}) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(SMTDropzoneComponent);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    const selected: File[][] = [];
    const rejected: SMTDropzoneRejection[][] = [];
    fixture.componentInstance.filesSelected.subscribe(files => selected.push(files));
    fixture.componentInstance.rejected.subscribe(list => rejected.push(list));
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const input = element.querySelector('input[type="file"]') as HTMLInputElement;
    const pick = (...files: File[]) => {
      Object.defineProperty(input, 'files', { value: files, configurable: true });
      input.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    };
    return { fixture, element, input, pick, selected, rejected };
  }

  it('is a label for a real, keyboard-reachable file input', () => {
    const { element, input } = render({ smtMultiple: true, smtAccept: '.pdf', smtHint: 'PDF up to 1 MB' });

    const label = element.querySelector(`label[for="${input.id}"]`)!;
    expect(label.textContent).toContain('Drop files here or');
    expect(label.textContent).toContain('PDF up to 1 MB');
    expect(input.multiple).toBe(true);
    expect(input.accept).toBe('.pdf');
    expect(input.tabIndex).not.toBe(-1);
  });

  it('passes accepted files on and lists rejected ones in an alert', () => {
    const { element, pick, selected, rejected } = render({ smtMultiple: true, smtAccept: '.pdf', smtMaxBytes: 1024 });

    pick(file('ok.pdf', 'application/pdf', 10), file('virus.exe'), file('huge.pdf', 'application/pdf', 4096));

    expect(selected.map(files => files.map(f => f.name))).toEqual([['ok.pdf']]);
    expect(rejected[0].map(r => `${r.file.name}:${r.reason}`)).toEqual(['virus.exe:type', 'huge.pdf:size']);
    const alert = element.querySelector('[role="alert"]')!;
    expect(alert.textContent).toContain('virus.exe: this file type is not accepted');
    expect(alert.textContent).toContain('huge.pdf: the file is larger than 1 KB');
  });

  it('takes one file when not multiple and clears the input so the same file can be picked again', () => {
    const { input, pick, selected } = render();

    pick(file('a.pdf'), file('b.pdf'));

    expect(selected).toEqual([[expect.objectContaining({ name: 'a.pdf' })]]);
    expect(input.value).toBe('');
  });

  it('accepts dropped files and marks the zone while dragging', () => {
    const { fixture, element, selected } = render({ smtMultiple: true });
    const zone = element.querySelector('.smt-dropzone')!;

    zone.dispatchEvent(Object.assign(new Event('dragover', { cancelable: true }), { dataTransfer: null }));
    fixture.detectChanges();
    expect(zone.classList).toContain('smt-dropzone--dragging');

    zone.dispatchEvent(Object.assign(new Event('drop', { cancelable: true }), { dataTransfer: { files: [file('dropped.pdf')] } }));
    fixture.detectChanges();
    expect(zone.classList).not.toContain('smt-dropzone--dragging');
    expect(selected.map(files => files.map(f => f.name))).toEqual([['dropped.pdf']]);
  });
});
