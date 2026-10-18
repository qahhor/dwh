import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  model,
  signal,
} from '@angular/core';
import type { Subscription } from 'rxjs';
import { fileId, fileList, type FileValue } from '@core/services/field-values';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '../ui-kit/components/button';
import { SMTControlComponent } from '../ui-kit/components/forms/control/control.component';
import { SMTDropzoneComponent } from '../ui-kit/components/dropzone';
import { FileUploadApi } from '../ui/file-upload.api';
import { EntitiesApi } from './entities.api';
import { fileName } from './entity-values';

/** The value a file field edits: one file, or the files of a field that holds several (ADR-0043). */
export type FileFieldValue = FileValue | string | (FileValue | string)[] | null;

/**
 * A file or image field (ADR-0032 4.7, plan 10/10, item 5.2; ADR-0043 for several files): the person picks a file,
 * the files module checks it — content and virus scan — and stores it, and the field holds its id; the save attaches
 * it to the record. The upload shows its progress and then that the file is being checked; a chosen file shows its
 * name with a link through its record (when the record exists) and a button to take it away. A field with `maxFiles`
 * holds several files up to that count. No thumbnails are made (ADR-0032 §19, B5): an image opens as it is.
 */
@Component({
  selector: 'smt-file-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, SMTControlComponent, SMTDropzoneComponent, TranslatePipe],
  host: { class: 'smt-file-field' },
  template: `
    <smt-control [smtLabel]="label()" [required]="required()" [smtError]="error() || failure()">
      @if (files().length > 0) {
        <ul class="file-list" [attr.aria-label]="label()">
          @for (file of files(); track file.id) {
            <li class="file-chosen">
              <span class="material-symbols-outlined" aria-hidden="true">{{ image() ? 'image' : 'description' }}</span>
              @if (href(file.id); as url) {
                <a class="file-name" [href]="url" target="_blank" rel="noopener">{{ file.name }}</a>
              } @else {
                <span class="file-name">{{ file.name }}</span>
              }
              <button
                smt-button
                type="button"
                smtVariant="ghost"
                smtSize="sm"
                smtIcon="close"
                [disabled]="disabled()"
                [attr.aria-label]="'ui.entity_form.file_remove' | t: { name: file.name }"
                (click)="remove(file.id)"
              ></button>
            </li>
          }
        </ul>
      }
      @if (open()) {
        <smt-dropzone
          [smtAccept]="accept()"
          [smtMultiple]="several()"
          [smtMaxBytes]="maxBytes() ?? 0"
          [smtHint]="hint()"
          [disabled]="disabled() || uploading()"
          (filesSelected)="upload($event)"
        />
      }
      @if (uploading()) {
        <div class="file-progress">
          <div
            class="file-bar"
            role="progressbar"
            aria-valuemin="0"
            aria-valuemax="100"
            [attr.aria-valuenow]="progress()"
            [attr.aria-label]="'ui.entity_form.file_progress' | t"
          >
            <span class="file-bar-fill" [style.width.%]="progress()"></span>
          </div>
          <p class="file-status" role="status">
            {{ (checking() ? 'ui.entity_form.file_checking' : 'ui.entity_form.file_uploading') | t }}
          </p>
        </div>
      }
      @if (several()) {
        <p class="file-status">
          {{ 'ui.entity_form.files_count' | t: { count: files().length, max: maxFiles() ?? 0 } }}
        </p>
      }
    </smt-control>
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .file-list {
        display: flex;
        flex-direction: column;
        gap: 4px;
        margin: 0 0 8px;
        padding: 0;
        list-style: none;
      }
      .file-chosen {
        display: flex;
        align-items: center;
        gap: 8px;
        min-width: 0;
      }
      .file-name {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .file-progress {
        margin-top: 6px;
      }
      .file-bar {
        height: 4px;
        overflow: hidden;
        border-radius: 2px;
        background: var(--border-color);
      }
      .file-bar-fill {
        display: block;
        height: 100%;
        background: var(--primary-color);
      }
      .file-status {
        margin: 4px 0 0;
        color: var(--text-muted);
        font-size: 0.8125rem;
      }
    `,
  ],
})
export class SMTFileFieldComponent {
  private readonly api = inject(EntitiesApi);

  private readonly uploads = inject(FileUploadApi);

  private readonly i18n = inject(I18nService);

  readonly label = input.required<string>();

  /** An image takes PNG, JPEG and WebP; a file any type unless `contentTypes` says otherwise. */
  readonly image = input(false, { transform: booleanAttribute });

  readonly contentTypes = input<readonly string[]>([]);

  readonly maxBytes = input<number | null>(null);

  /** The most files of a field that holds several; null for one file. */
  readonly maxFiles = input<number | null>(null);

  readonly required = input(false, { transform: booleanAttribute });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly error = input('');

  /** The record's entity and id, so a chosen file opens through its record; none while the record is new. */
  readonly entity = input<string | null>(null);

  readonly recordId = input<number | null>(null);

  /** The files the field holds: ids, or the files a record reads back. */
  readonly value = model<FileFieldValue>(null);

  readonly uploading = signal(false);

  /** The share of the upload sent, 0 to 100. */
  readonly progress = signal(0);

  readonly failure = signal('');

  readonly several = computed(() => this.maxFiles() !== null);

  /** Sent in full: the files module checks the file — content and virus scan — before it answers. */
  readonly checking = computed(() => this.uploading() && this.progress() >= 100);

  readonly accept = computed(() => this.contentTypes().join(','));

  readonly hint = computed(() =>
    this.contentTypes().length ? this.i18n.translate('ui.entity_form.file_types', { types: this.accept() }) : '',
  );

  /** The chosen files with their names, in their order. */
  readonly files = computed(() =>
    fileList(this.value()).map((file) => ({ id: fileId(file) ?? '', name: fileName(file) })),
  );

  /** Whether another file may be picked: an empty single field, or one of several below its limit. */
  readonly open = computed(() =>
    this.several() ? this.files().length < (this.maxFiles() ?? 0) : this.files().length === 0,
  );

  /** The name of the one file of a single field, or null. */
  readonly chosen = computed(() => (this.several() ? null : (this.files()[0]?.name ?? null)));

  private request: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
  }

  href(id: string): string | null {
    const entity = this.entity();
    const record = this.recordId();
    return id && entity && record ? this.api.fileUrl(entity, record, id) : null;
  }

  remove(id: string): void {
    if (!this.several()) {
      this.value.set(null);
      return;
    }
    const left = fileList(this.value()).filter((file) => fileId(file) !== id);
    this.value.set(left.length ? left : null);
  }

  /** Uploads the first picked file; a field of several files takes the others one after another. */
  upload(files: File[]): void {
    const room = this.several() ? (this.maxFiles() ?? 0) - this.files().length : 1;
    const queue = files.slice(0, Math.max(0, room));
    if (queue.length === 0) return;
    this.failure.set('');
    this.next(queue);
  }

  private next(queue: File[]): void {
    const [file, ...rest] = queue;
    if (!file) return;
    this.uploading.set(true);
    this.progress.set(0);
    this.request?.unsubscribe();
    this.request = this.uploads.upload(file).subscribe({
      next: (step) => {
        if (step.kind === 'progress') {
          this.progress.set(step.percent);
          return;
        }
        this.uploading.set(false);
        const stored: FileValue = {
          id: step.file.id ?? '',
          name: step.file.originalName ?? file.name,
          size: step.file.sizeBytes,
          contentType: step.file.mimeType,
        };
        this.value.set(this.several() ? [...fileList(this.value()), stored] : stored);
        this.next(rest);
      },
      error: (problem: { error?: { detail?: string }; detail?: string }) => {
        this.uploading.set(false);
        this.failure.set(
          problem?.error?.detail || problem?.detail || this.i18n.translate('ui.entity_form.file_failed'),
        );
      },
    });
  }
}
