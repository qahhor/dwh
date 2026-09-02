import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiMarkdownEditorComponent } from './ui-markdown-editor.component';

describe('UiMarkdownEditorComponent', () => {
  it('names the formatting toolbar and every icon-only action', async () => {
    await TestBed.configureTestingModule({ imports: [UiMarkdownEditorComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiMarkdownEditorComponent);
    fixture.detectChanges();

    const toolbar = fixture.nativeElement.querySelector('[role="toolbar"]') as HTMLElement;
    const actions = Array.from(toolbar.querySelectorAll('.tool-btn')) as HTMLButtonElement[];

    expect(toolbar.getAttribute('aria-label')).toBe('Форматирование Markdown');
    expect(actions.length).toBeGreaterThan(0);
    expect(actions.every(action => Boolean(action.getAttribute('aria-label')))).toBe(true);
    expect(actions.every(action => action.querySelector('[aria-hidden="true"]'))).toBe(true);
  });

  it('connects labelled editor and preview tab panels', async () => {
    await TestBed.configureTestingModule({ imports: [UiMarkdownEditorComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiMarkdownEditorComponent);
    fixture.componentRef.setInput('ariaLabel', 'Описание задачи');
    fixture.detectChanges();

    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    const label = fixture.nativeElement.querySelector(`label[for="${textarea.id}"]`) as HTMLLabelElement;
    const tabs = Array.from(fixture.nativeElement.querySelectorAll('[role="tab"]')) as HTMLButtonElement[];

    expect(label.textContent).toContain('Описание задачи');
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    expect(fixture.nativeElement.querySelector(`#${tabs[0].getAttribute('aria-controls')}[role="tabpanel"]`)).not.toBeNull();

    tabs[1].click();
    fixture.detectChanges();

    expect(tabs[1].getAttribute('aria-selected')).toBe('true');
    expect(fixture.nativeElement.querySelector(`#${tabs[1].getAttribute('aria-controls')}[role="tabpanel"]`)).not.toBeNull();
  });

  it('does not create executable links in preview mode', async () => {
    await TestBed.configureTestingModule({ imports: [UiMarkdownEditorComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiMarkdownEditorComponent);
    fixture.componentInstance.value = '[safe](mailto:help@example.com) [unsafe](javascript:alert(1))';
    fixture.componentInstance.mode = 'preview';
    fixture.detectChanges();

    const links = Array.from(fixture.nativeElement.querySelectorAll('.md-preview-pane a')) as HTMLAnchorElement[];
    expect(links).toHaveLength(1);
    expect(links[0].textContent).toBe('safe');
    expect(links[0].getAttribute('href')).toBe('mailto:help@example.com');
    expect(fixture.nativeElement.querySelector('.md-preview-pane')?.textContent).toContain('unsafe');
  });
});
