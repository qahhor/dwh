import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { replaceMarkdownLinksWithSafeAnchors } from './markdown-link-sanitizer';

@Component({
  selector: 'ui-markdown-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  template: ` <div class="md-rendered-content" [innerHTML]="renderedHtml()"></div> `,
  styleUrl: './ui-markdown-view.component.css',
})
export class UiMarkdownViewComponent {
  readonly content = input<string | undefined>('');
  readonly renderedHtml = computed(() => this.parseMarkdown(this.content() || ''));

  private parseMarkdown(text: string): string {
    if (!text || !text.trim()) {
      return '';
    }

    let html = this.escapeHtml(text);

    // Code blocks ``` ... ```
    html = html.replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>');

    // Inline code `...`
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

    // Headers
    html = html.replace(/^### (.*$)/gim, '<h3>$1</h3>');
    html = html.replace(/^## (.*$)/gim, '<h2>$1</h2>');
    html = html.replace(/^# (.*$)/gim, '<h1>$1</h1>');

    // Bold, Italic, Strike
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    html = html.replace(/~~([^~]+)~~/g, '<del>$1</del>');

    // Blockquote
    html = html.replace(/^> (.*$)/gim, '<blockquote>$1</blockquote>');

    // Checklist: - [ ] and - [x]
    html = html.replace(/^- \[ \] (.*$)/gim, '<div><label><input type="checkbox" disabled /> $1</label></div>');
    html = html.replace(
      /^- \[x\] (.*$)/gim,
      '<div><label><input type="checkbox" checked disabled /> <del>$1</del></label></div>',
    );

    // Lists
    html = html.replace(/^\- (.*$)/gim, '<li>$1</li>');
    html = html.replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>');

    // Links [text](url)
    html = replaceMarkdownLinksWithSafeAnchors(html);

    // Linebreaks
    html = html.replace(/\n/g, '<br/>');

    return html;
  }

  private escapeHtml(str: string): string {
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }
}
