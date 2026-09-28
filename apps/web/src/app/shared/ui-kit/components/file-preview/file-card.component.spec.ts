import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { testI18n } from '@shared/ui-kit/i18n/test-messages';
import { SMTFileCardComponent } from './file-card.component';

describe('SMTFileCardComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n('en', 'en') }] });
  });

  function render(inputs: Record<string, unknown>) {
    const fixture = TestBed.createComponent(SMTFileCardComponent);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    const card = fixture.nativeElement as HTMLElement;
    const button = (label: string) => card.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
    const labels = () => Array.from(card.querySelectorAll('button')).map((item) => item.getAttribute('aria-label'));
    return { fixture, card, button, labels };
  }

  it('downloads from its name and its download button, and reports each request', () => {
    const { fixture, card } = render({ name: 'plan.xlsx', size: 2048 });
    const events: string[] = [];
    fixture.componentInstance.download.subscribe(() => events.push('download'));
    const buttons = Array.from(card.querySelectorAll('button[aria-label="Download plan.xlsx"]')) as HTMLButtonElement[];

    expect(buttons).toHaveLength(2);
    expect(buttons[0].title).toBe('plan.xlsx');
    expect(card.querySelector('.smt-file-card__size')?.textContent).toBe('2 kB');
    buttons.forEach((item) => item.click());
    expect(events).toEqual(['download', 'download']);
  });

  it('tells the kind of file from its name when there is no media type', () => {
    const icon = (name: string) =>
      render({ name }).card.querySelector('.smt-file-card__icon .material-symbols-outlined')?.textContent?.trim();

    expect(icon('dump.zip')).toBe('folder_zip');
    expect(icon('report.pdf')).toBe('picture_as_pdf');
    expect(icon('photo.JPG')).toBe('image');
  });

  it('offers a preview only for an image, and not where the caller turns it off', () => {
    const image = render({ name: 'photo.png', mimeType: 'image/png' });
    const previews: unknown[] = [];
    image.fixture.componentInstance.preview.subscribe(() => previews.push(true));
    image.button('Preview photo.png')!.click();
    expect(previews).toHaveLength(1);

    expect(
      render({ name: 'photo.png', mimeType: 'image/png', smtPreview: false }).button('Preview photo.png'),
    ).toBeNull();
    expect(render({ name: 'page.html', mimeType: 'text/html' }).button('Preview page.html')).toBeNull();
  });

  it('offers removal only when allowed, disabled while a removal runs', () => {
    expect(render({ name: 'plan.xlsx' }).button('Remove plan.xlsx')).toBeNull();

    const { fixture, button } = render({ name: 'plan.xlsx', smtRemovable: true });
    const removals: unknown[] = [];
    fixture.componentInstance.remove.subscribe(() => removals.push(true));
    button('Remove plan.xlsx')!.click();
    expect(removals).toHaveLength(1);

    fixture.componentRef.setInput('smtRemoving', true);
    fixture.detectChanges();
    expect(button('Remove plan.xlsx')!.disabled).toBe(true);
  });

  it('hides the size when it is not known', () => {
    const { card, labels } = render({ name: 'notes.md' });

    expect(card.querySelector('.smt-file-card__size')).toBeNull();
    expect(labels()).toEqual(['Download notes.md', 'Download notes.md']);
  });
});
