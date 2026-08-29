import { Component, Input, Output, EventEmitter, signal, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

@Component({
  selector: 'ui-markdown-editor',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="md-editor-container" [class.focused]="isFocused">
      <!-- Toolbar -->
      <div class="md-toolbar">
        <div class="toolbar-actions">
          <button type="button" class="tool-btn" (click)="insertFormat('bold')" title="Жирный (Ctrl+B)">
            <span class="material-symbols-outlined">format_bold</span>
          </button>
          <button type="button" class="tool-btn" (click)="insertFormat('italic')" title="Курсив (Ctrl+I)">
            <span class="material-symbols-outlined">format_italic</span>
          </button>
          <button type="button" class="tool-btn" (click)="insertFormat('strike')" title="Зачеркнутый">
            <span class="material-symbols-outlined">format_strikethrough</span>
          </button>
          <span class="tool-sep"></span>

          <button type="button" class="tool-btn" (click)="insertFormat('h2')" title="Заголовок">
            <span class="material-symbols-outlined">title</span>
          </button>
          <button type="button" class="tool-btn" (click)="insertFormat('bullet-list')" title="Маркированный список">
            <span class="material-symbols-outlined">format_list_bulleted</span>
          </button>
          <button type="button" class="tool-btn" (click)="insertFormat('num-list')" title="Нумерованный список">
            <span class="material-symbols-outlined">format_list_numbered</span>
          </button>
          <button type="button" class="tool-btn" (click)="insertFormat('task-list')" title="Чек-лист задач">
            <span class="material-symbols-outlined">checklist</span>
          </button>
          <span class="tool-sep"></span>

          <button type="button" class="tool-btn" (click)="insertFormat('code')" title="Код">
            <span class="material-symbols-outlined">code</span>
          </button>
          <button type="button" class="tool-btn" (click)="insertFormat('quote')" title="Цитата">
            <span class="material-symbols-outlined">format_quote</span>
          </button>
          <button type="button" class="tool-btn" (click)="insertFormat('link')" title="Ссылка">
            <span class="material-symbols-outlined">link</span>
          </button>
          <button type="button" class="tool-btn" (click)="insertFormat('table')" title="Таблица">
            <span class="material-symbols-outlined">table</span>
          </button>
        </div>

        <!-- Mode Toggle Tabs -->
        <div class="mode-tabs">
          <button
            type="button"
            class="mode-btn"
            [class.active]="mode === 'edit'"
            (click)="mode = 'edit'"
          >
            <span class="material-symbols-outlined ico">edit_note</span>
            <span>Редактор</span>
          </button>
          <button
            type="button"
            class="mode-btn"
            [class.active]="mode === 'preview'"
            (click)="mode = 'preview'"
          >
            <span class="material-symbols-outlined ico">visibility</span>
            <span>Предпросмотр</span>
          </button>
        </div>
      </div>

      <!-- Editor Content -->
      <div class="md-body">
        <!-- Textarea Mode -->
        <textarea
          #textareaRef
          *ngIf="mode === 'edit'"
          class="md-textarea"
          [rows]="rows"
          [placeholder]="placeholder"
          [ngModel]="value"
          (ngModelChange)="onTextChange($event)"
          (focus)="isFocused = true"
          (blur)="isFocused = false"
          (keydown)="handleKeydown($event)"
        ></textarea>

        <!-- Preview Mode -->
        <div
          *ngIf="mode === 'preview'"
          class="md-preview-pane"
          [innerHTML]="renderMarkdown(value)"
        ></div>
      </div>
    </div>
  `,
  styles: [`
    .md-editor-container {
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      overflow: hidden;
      display: flex;
      flex-direction: column;
      transition: border-color 0.15s ease, box-shadow 0.15s ease;
    }
    .md-editor-container.focused {
      border-color: var(--primary);
      box-shadow: 0 0 0 1px var(--primary);
    }

    .md-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 6px;
      padding: 4px 8px;
      background-color: var(--bg-hover);
      border-bottom: 1px solid var(--border-color);
      flex-wrap: wrap;
    }

    .toolbar-actions {
      display: flex;
      align-items: center;
      gap: 2px;
      flex-wrap: wrap;
    }

    .tool-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      border-radius: var(--radius-xs);
      width: 26px;
      height: 26px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      padding: 0;
      transition: all 0.1s ease;
    }
    .tool-btn:hover {
      background-color: var(--bg-surface);
      color: var(--text-main);
    }
    .tool-btn .material-symbols-outlined { font-size: 16px; }

    .tool-sep {
      width: 1px;
      height: 16px;
      background-color: var(--border-color);
      margin: 0 4px;
    }

    .mode-tabs {
      display: flex;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-xs);
      padding: 1px;
      gap: 2px;
    }
    .mode-btn {
      border: none;
      background: transparent;
      padding: 2px 8px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      border-radius: 2px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      transition: all 0.1s ease;
    }
    .mode-btn .ico { font-size: 14px; }
    .mode-btn.active {
      background-color: var(--primary);
      color: #fff;
    }

    .md-body {
      display: flex;
      flex-direction: column;
      position: relative;
    }

    .md-textarea {
      width: 100%;
      border: none;
      outline: none;
      background: transparent;
      color: var(--text-main);
      padding: 8px 10px;
      font-family: inherit;
      font-size: 13px;
      line-height: 1.5;
      resize: vertical;
      min-height: 90px;
    }

    .md-preview-pane {
      padding: 10px 12px;
      min-height: 90px;
      max-height: 350px;
      overflow-y: auto;
      font-size: 13px;
      line-height: 1.6;
      color: var(--text-main);
      background-color: var(--bg-surface);
    }

    /* Markdown Rendered Typography */
    :host ::ng-deep .md-preview-pane h1,
    :host ::ng-deep .md-preview-pane h2,
    :host ::ng-deep .md-preview-pane h3,
    :host ::ng-deep .md-preview-pane h4 {
      margin-top: 8px;
      margin-bottom: 4px;
      font-weight: 600;
      color: var(--text-main);
    }
    :host ::ng-deep .md-preview-pane h1 { font-size: 16px; border-bottom: 1px solid var(--border-color); padding-bottom: 4px; }
    :host ::ng-deep .md-preview-pane h2 { font-size: 15px; }
    :host ::ng-deep .md-preview-pane h3 { font-size: 14px; }
    :host ::ng-deep .md-preview-pane p { margin: 4px 0; }
    :host ::ng-deep .md-preview-pane ul,
    :host ::ng-deep .md-preview-pane ol { padding-left: 20px; margin: 4px 0; }
    :host ::ng-deep .md-preview-pane li { margin: 2px 0; }
    :host ::ng-deep .md-preview-pane code {
      font-family: monospace;
      font-size: 12px;
      background-color: var(--bg-hover);
      padding: 2px 5px;
      border-radius: 3px;
      border: 1px solid var(--border-color);
    }
    :host ::ng-deep .md-preview-pane pre {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 8px 10px;
      overflow-x: auto;
      margin: 6px 0;
    }
    :host ::ng-deep .md-preview-pane pre code {
      border: none;
      padding: 0;
      background: transparent;
    }
    :host ::ng-deep .md-preview-pane blockquote {
      margin: 6px 0;
      padding: 4px 10px;
      border-left: 3px solid var(--primary);
      background-color: var(--bg-hover);
      color: var(--text-muted);
      border-radius: 0 var(--radius-xs) var(--radius-xs) 0;
    }
    :host ::ng-deep .md-preview-pane table {
      width: 100%;
      border-collapse: collapse;
      margin: 8px 0;
      font-size: 12px;
    }
    :host ::ng-deep .md-preview-pane th,
    :host ::ng-deep .md-preview-pane td {
      border: 1px solid var(--border-color);
      padding: 6px 8px;
      text-align: left;
    }
    :host ::ng-deep .md-preview-pane th {
      background-color: var(--bg-hover);
      font-weight: 600;
    }
    :host ::ng-deep .md-preview-pane input[type="checkbox"] {
      margin-right: 6px;
      accent-color: var(--primary);
    }
    :host ::ng-deep .md-preview-pane a {
      color: var(--primary);
      text-decoration: underline;
    }
  `]
})
export class UiMarkdownEditorComponent {
  @Input() value = '';
  @Input() placeholder = 'Напишите текст задачи (поддерживается Markdown)...';
  @Input() rows = 4;
  @Output() valueChange = new EventEmitter<string>();

  @ViewChild('textareaRef') textareaRef?: ElementRef<HTMLTextAreaElement>;

  mode: 'edit' | 'preview' = 'edit';
  isFocused = false;

  constructor(private sanitizer: DomSanitizer) {}

  onTextChange(newVal: string) {
    this.value = newVal;
    this.valueChange.emit(newVal);
  }

  handleKeydown(event: KeyboardEvent) {
    if ((event.ctrlKey || event.metaKey) && event.key === 'b') {
      event.preventDefault();
      this.insertFormat('bold');
    } else if ((event.ctrlKey || event.metaKey) && event.key === 'i') {
      event.preventDefault();
      this.insertFormat('italic');
    }
  }

  insertFormat(type: string) {
    const el = this.textareaRef?.nativeElement;
    if (!el) {
      if (type === 'bold') this.onTextChange((this.value || '') + '**жирный текст**');
      return;
    }

    const start = el.selectionStart;
    const end = el.selectionEnd;
    const current = this.value || '';
    const selected = current.substring(start, end);

    let replacement = '';
    let cursorOffset = 0;

    switch (type) {
      case 'bold':
        replacement = `**${selected || 'жирный текст'}**`;
        cursorOffset = selected ? replacement.length : 2;
        break;
      case 'italic':
        replacement = `*${selected || 'курсив'}*`;
        cursorOffset = selected ? replacement.length : 1;
        break;
      case 'strike':
        replacement = `~~${selected || 'зачеркнутый текст'}~~`;
        cursorOffset = selected ? replacement.length : 2;
        break;
      case 'h2':
        replacement = `\n## ${selected || 'Заголовок'}\n`;
        cursorOffset = replacement.length;
        break;
      case 'bullet-list':
        replacement = `\n- ${selected || 'Элемент списка'}\n- Второй элемент\n`;
        cursorOffset = replacement.length;
        break;
      case 'num-list':
        replacement = `\n1. ${selected || 'Первый пункт'}\n2. Второй пункт\n`;
        cursorOffset = replacement.length;
        break;
      case 'task-list':
        replacement = `\n- [ ] ${selected || 'Подзадача или пункт чек-листа'}\n- [x] Выполненный пункт\n`;
        cursorOffset = replacement.length;
        break;
      case 'code':
        if (selected.includes('\n')) {
          replacement = `\n\`\`\`\n${selected || 'code block'}\n\`\`\`\n`;
        } else {
          replacement = `\`${selected || 'code'}\``;
        }
        cursorOffset = replacement.length;
        break;
      case 'quote':
        replacement = `\n> ${selected || 'Цитата или важное примечание'}\n`;
        cursorOffset = replacement.length;
        break;
      case 'link':
        replacement = `[${selected || 'Текст ссылки'}](https://)`;
        cursorOffset = replacement.length - 1;
        break;
      case 'table':
        replacement = `\n| Параметр | Значение |\n| --- | --- |\n| Статус | Одобрено |\n`;
        cursorOffset = replacement.length;
        break;
      default:
        return;
    }

    const updated = current.substring(0, start) + replacement + current.substring(end);
    this.onTextChange(updated);

    setTimeout(() => {
      el.focus();
      el.setSelectionRange(start + cursorOffset, start + cursorOffset);
    }, 0);
  }

  renderMarkdown(text: string): SafeHtml {
    if (!text || !text.trim()) {
      return this.sanitizer.bypassSecurityTrustHtml('<span style="color:var(--text-muted);font-style:italic;">Предпросмотр пуст</span>');
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
    html = html.replace(/^- \[ \] (.*$)/gim, '<div><input type="checkbox" disabled /> $1</div>');
    html = html.replace(/^- \[x\] (.*$)/gim, '<div><input type="checkbox" checked disabled /> <del>$1</del></div>');

    // Unordered lists
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
