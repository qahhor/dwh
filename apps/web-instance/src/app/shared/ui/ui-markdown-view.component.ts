import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

@Component({
  selector: 'ui-markdown-view',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="md-rendered-content" [innerHTML]="renderedHtml"></div>
  `,
  styles: [`
    .md-rendered-content {
      font-size: 13px;
      line-height: 1.6;
      color: var(--text-main);
      word-break: break-word;
    }

    :host ::ng-deep h1,
    :host ::ng-deep h2,
    :host ::ng-deep h3,
    :host ::ng-deep h4 {
      margin-top: 10px;
      margin-bottom: 4px;
      font-weight: 600;
      color: var(--text-main);
    }
    :host ::ng-deep h1 { font-size: 16px; border-bottom: 1px solid var(--border-color); padding-bottom: 4px; }
    :host ::ng-deep h2 { font-size: 15px; }
    :host ::ng-deep h3 { font-size: 14px; }
    :host ::ng-deep p { margin: 4px 0; }
    :host ::ng-deep ul,
    :host ::ng-deep ol { padding-left: 20px; margin: 4px 0; }
    :host ::ng-deep li { margin: 2px 0; }
    :host ::ng-deep code {
      font-family: monospace;
      font-size: 12px;
      background-color: var(--bg-hover);
      padding: 2px 5px;
      border-radius: 3px;
      border: 1px solid var(--border-color);
    }
    :host ::ng-deep pre {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 8px 10px;
      overflow-x: auto;
      margin: 6px 0;
    }
    :host ::ng-deep pre code {
      border: none;
      padding: 0;
      background: transparent;
    }
    :host ::ng-deep blockquote {
      margin: 6px 0;
      padding: 4px 10px;
      border-left: 3px solid var(--primary);
      background-color: var(--bg-hover);
      color: var(--text-muted);
      border-radius: 0 var(--radius-xs) var(--radius-xs) 0;
    }
    :host ::ng-deep table {
      width: 100%;
      border-collapse: collapse;
      margin: 8px 0;
      font-size: 12px;
    }
    :host ::ng-deep th,
    :host ::ng-deep td {
      border: 1px solid var(--border-color);
      padding: 6px 8px;
      text-align: left;
    }
    :host ::ng-deep th {
      background-color: var(--bg-hover);
      font-weight: 600;
    }
    :host ::ng-deep input[type="checkbox"] {
      margin-right: 6px;
      accent-color: var(--primary);
    }
    :host ::ng-deep a {
      color: var(--primary);
      text-decoration: underline;
    }
  `]
})
export class UiMarkdownViewComponent implements OnChanges {
  @Input() content: string | undefined = '';
  renderedHtml: SafeHtml = '';


  constructor(private sanitizer: DomSanitizer) {}

  ngOnChanges() {
    this.renderedHtml = this.parseMarkdown(this.content || '');
  }

  private parseMarkdown(text: string): SafeHtml {
    if (!text || !text.trim()) {
      return this.sanitizer.bypassSecurityTrustHtml('');
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
    html = html.replace(/^- \[x\] (.*$)/gim, '<div><label><input type="checkbox" checked disabled /> <del>$1</del></label></div>');

    // Lists
    html = html.replace(/^\- (.*$)/gim, '<li>$1</li>');
    html = html.replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>');

    // Links [text](url)
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

    // Linebreaks
    html = html.replace(/\n/g, '<br/>');

    return this.sanitizer.bypassSecurityTrustHtml(html);
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
