import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiMarkdownViewComponent } from './ui-markdown-view.component';

describe('UiMarkdownViewComponent security', () => {
  it('renders safe links but never creates executable links from markdown', async () => {
    await TestBed.configureTestingModule({ imports: [UiMarkdownViewComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiMarkdownViewComponent);
    fixture.componentRef.setInput(
      'content',
      '[safe](https://example.com/docs) [js](javascript:alert(1)) [data](data:text/html,boom) [mixed](JaVaScRiPt:alert(1))'
    );
    fixture.detectChanges();

    const links = Array.from(fixture.nativeElement.querySelectorAll('a')) as HTMLAnchorElement[];
    expect(links).toHaveLength(1);
    expect(links[0].textContent).toBe('safe');
    expect(links[0].getAttribute('href')).toBe('https://example.com/docs');
    expect(fixture.nativeElement.textContent).toContain('js');
    expect(fixture.nativeElement.textContent).toContain('data');
    expect(fixture.nativeElement.textContent).toContain('mixed');
  });
});
