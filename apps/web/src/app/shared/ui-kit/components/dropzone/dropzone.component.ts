/* Our code, after the idea of the kit's `smt-dropzone` (smartup-ui-kit@6472beb,
 * components/dropzone). See ADR-0015 rule 2.
 *
 * The kit's zone was a div with role="button" and a hidden input, and it
 * dropped files that failed its `accept` check without a word. This one is
 * a <label> for a real, visually hidden file input, so the keyboard reaches
 * it and a screen reader names it (the pattern our upload already used),
 * with a visible focus ring while the input is focused. Files that fail the
 * type or size check are listed in an alert instead of vanishing. It only
 * picks files: uploading is the caller's job.
 *
 *   <smt-dropzone [smtMultiple]="true" smtAccept=".xlsx" [smtMaxBytes]="20971520"
 *     smtHint="XLSX, up to 20 MB" (filesSelected)="upload($event)" /> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  output,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { SMTI18nService } from '../../i18n';

export type SMTDropzoneRejection = { readonly file: File; readonly reason: 'type' | 'size' };

let nextDropzoneId = 0;

/** Human size for messages: 20 MB, 512 KB. */
export function formatFileSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${Math.round((bytes / (1024 * 1024)) * 10) / 10} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

/** Whether `file` matches an `accept` list such as ".pdf,image/*,application/zip". */
export function acceptsFile(file: File, accept: string): boolean {
  const rules = accept
    .split(',')
    .map(rule => rule.trim().toLowerCase())
    .filter(Boolean);
  if (!rules.length) return true;
  const name = file.name.toLowerCase();
  const type = (file.type || '').toLowerCase();
  return rules.some(rule => {
    if (rule.startsWith('.')) return name.endsWith(rule);
    if (rule.endsWith('/*')) return type.startsWith(rule.slice(0, -1));
    return type === rule;
  });
}

@Component({
  selector: 'smt-dropzone',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './dropzone.component.html',
  styleUrl: './dropzone.scss',
  host: { class: 'smt-dropzone-host' },
})
export class SMTDropzoneComponent {
  readonly i18n = inject(SMTI18nService);

  readonly accept = input('', { alias: 'smtAccept' });

  readonly multiple = input(false, { alias: 'smtMultiple', transform: booleanAttribute });

  /** Largest accepted file in bytes; 0 means no limit here (the server still checks). */
  readonly maxBytes = input(0, { alias: 'smtMaxBytes' });

  /** Line under the prompt, e.g. the accepted formats and size. */
  readonly hint = input('', { alias: 'smtHint' });

  readonly disabled = input(false, { transform: booleanAttribute });

  /** Files that passed the checks, in the order they were given. */
  readonly filesSelected = output<File[]>();

  readonly rejected = output<SMTDropzoneRejection[]>();

  readonly dragging = signal(false);

  readonly rejections = signal<readonly SMTDropzoneRejection[]>([]);

  readonly inputId = `smt-dropzone-${nextDropzoneId++}`;

  onDragOver(event: DragEvent): void {
    if (this.disabled()) return;
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    if (this.disabled()) return;
    this.take(Array.from(event.dataTransfer?.files ?? []));
  }

  onChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.take(Array.from(input.files ?? []));
    // Picking the same file again must fire change again.
    input.value = '';
  }

  rejectionText(rejection: SMTDropzoneRejection): string {
    const messages = this.i18n.messages().dropzone;
    return rejection.reason === 'type'
      ? messages.rejectedType(rejection.file.name)
      : messages.rejectedSize(rejection.file.name, formatFileSize(this.maxBytes()));
  }

  private take(files: File[]): void {
    const given = this.multiple() ? files : files.slice(0, 1);
    const accepted: File[] = [];
    const rejected: SMTDropzoneRejection[] = [];
    for (const file of given) {
      if (!acceptsFile(file, this.accept())) rejected.push({ file, reason: 'type' });
      else if (this.maxBytes() > 0 && file.size > this.maxBytes()) rejected.push({ file, reason: 'size' });
      else accepted.push(file);
    }
    this.rejections.set(rejected);
    if (rejected.length) this.rejected.emit(rejected);
    if (accepted.length) this.filesSelected.emit(accepted);
  }
}
