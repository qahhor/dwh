import { ChangeDetectionStrategy, Component, DestroyRef, signal, inject, input, output } from '@angular/core';

import { HttpClient, HttpEventType } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { SMTDropzoneComponent } from '../ui-kit/components/dropzone';
import { SMTFileCardComponent, SMTFilePreviewService } from '../ui-kit/components/file-preview';
import { TaskFile } from '@core/models/task.models';
import { ToastService } from '@core/services/toast.service';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';

/** One file in the upload queue. */
export interface QueuedUpload {
  readonly id: number;
  readonly file: File;
  readonly status: 'queued' | 'uploading' | 'failed';
  readonly progress: number;
  readonly error?: string;
}

/** What the upload endpoint answers: the stored file. */
interface UploadedFile {
  id: string;
  originalName?: string;
  sizeBytes?: number;
  mimeType?: string;
  createdAt?: string;
}

@Component({
  selector: 'ui-file-upload',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTDropzoneComponent, SMTFileCardComponent],
  template: `
    <div class="file-upload-wrapper">
      <!-- Picking files: the shared dropzone (a label for a real file input). -->
      @if (canUpload()) {
        <smt-dropzone
          [smtMultiple]="multiple()"
          [smtHint]="'ui.file_upload.do_50_mb_na_fayl_pdf_png_jpg_docx_zip_i_dr' | t"
          (filesSelected)="uploadFiles($event)"
        ></smt-dropzone>
      }

      <!-- Upload queue: one file at a time, each with its own progress and actions. -->
      @if (queue().length > 0) {
        <ul class="upload-queue" [attr.aria-label]="'ui.file_upload.queue' | t">
          @for (item of queue(); track trackQueued($index, item)) {
            <li class="queue-item" [class.failed]="item.status === 'failed'">
              <span class="queue-name">{{ item.file.name }}</span>
              @if (item.status === 'uploading') {
                <div
                  class="queue-progress"
                  role="progressbar"
                  [attr.aria-label]="'ui.file_upload.upload_progress' | t: { progress: item.progress }"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  [attr.aria-valuenow]="item.progress"
                  [attr.aria-valuetext]="item.progress + '%'"
                >
                  <div class="progress-track"><div class="progress-fill" [style.width.%]="item.progress"></div></div>
                </div>
              }
              @if (item.status === 'queued') {
                <span class="queue-state">{{ 'ui.file_upload.queued' | t }}</span>
              }
              @if (item.status === 'failed') {
                <span class="queue-state queue-error" role="alert">{{ item.error }}</span>
              }
              <div class="file-actions">
                @if (item.status === 'failed') {
                  <button
                    type="button"
                    class="action-btn"
                    (click)="retry(item)"
                    [attr.aria-label]="'ui.file_upload.retry_named' | t: { name: item.file.name }"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
                  </button>
                }
                <button
                  type="button"
                  class="action-btn delete"
                  (click)="cancel(item)"
                  [attr.aria-label]="
                    (item.status === 'failed' ? 'ui.file_upload.dismiss_named' : 'ui.file_upload.cancel_named')
                      | t: { name: item.file.name }
                  "
                >
                  <span class="material-symbols-outlined" aria-hidden="true">close</span>
                </button>
              </div>
            </li>
          }
        </ul>
      }

      <!-- File Attachment List -->
      @if (files() && files().length > 0) {
        <div class="attachments-list" role="list" [attr.aria-label]="'ui.file_upload.prikreplennye_fayly' | t">
          @for (file of files(); track trackFile($index, file)) {
            <smt-file-card
              role="listitem"
              [name]="file.fileName"
              [mimeType]="file.mimeType"
              [size]="file.sizeBytes"
              [smtRemovable]="canDelete()"
              (download)="downloadFile(file)"
              (preview)="previewFile(file)"
              (remove)="removeFile(file)"
            />
          }
        </div>
      }

      @if ((!files() || files().length === 0) && !canUpload()) {
        <div class="empty-files">
          <span class="material-symbols-outlined empty-icon" aria-hidden="true">attach_file</span>
          <span>{{ 'ui.file_upload.net_prikreplennyh_faylov' | t }}</span>
        </div>
      }
    </div>
  `,
  styleUrl: './ui-file-upload.component.css',
})
export class UiFileUploadComponent {
  private readonly preview = inject(SMTFilePreviewService);
  private readonly uiI18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly canUpload = input(true);
  readonly canDelete = input(true);
  readonly multiple = input(true);

  readonly files = input<TaskFile[]>([]);

  readonly fileAttached = output<TaskFile>();
  readonly fileRemoved = output<TaskFile>();

  /** Files waiting, uploading or failed; a file leaves the queue once it is attached. */
  readonly queue = signal<QueuedUpload[]>([]);

  private nextQueueId = 0;
  private current: Subscription | null = null;

  constructor(
    private http: HttpClient,
    private toast: ToastService,
  ) {
    this.destroyRef.onDestroy(() => this.current?.unsubscribe());
  }

  uploadFiles(filesToUpload: File[]) {
    const added = filesToUpload.map((file) => ({
      id: this.nextQueueId++,
      file,
      status: 'queued' as const,
      progress: 0,
    }));
    this.queue.update((queue) => [...queue, ...added]);
    this.pump();
  }

  retry(item: QueuedUpload) {
    this.patch(item.id, { status: 'queued', progress: 0, error: undefined });
    this.pump();
  }

  /** Cancels a queued or running upload, or dismisses a failed one. */
  cancel(item: QueuedUpload) {
    if (item.status === 'uploading') {
      this.current?.unsubscribe();
      this.current = null;
    }
    this.queue.update((queue) => queue.filter((entry) => entry.id !== item.id));
    this.pump();
  }

  trackQueued(_: number, item: QueuedUpload) {
    return item.id;
  }

  downloadFile(file: TaskFile) {
    window.open(`/api/v1/files/${file.fileId}/download`, '_blank', 'noopener,noreferrer');
  }

  removeFile(file: TaskFile) {
    this.fileRemoved.emit(file);
  }

  /** Opens the attached images in the preview, starting at this one. */
  previewFile(file: TaskFile) {
    const previews = this.files().map((item) => ({
      name: item.fileName,
      mimeType: item.mimeType,
      url: `/api/v1/files/${item.fileId}/download`,
      item,
    }));
    const chosen = previews.find((preview) => preview.item === file);
    if (!chosen) return;
    this.preview.open(previews, chosen, (preview) => this.downloadFile((preview as typeof chosen).item));
  }

  trackFile(_index: number, file: TaskFile): string {
    return file.fileId;
  }

  /** Starts the next queued file when nothing is uploading. */
  private pump() {
    if (this.current) return;
    const next = this.queue().find((item) => item.status === 'queued');
    if (!next) return;
    this.patch(next.id, { status: 'uploading', progress: 0 });

    const formData = new FormData();
    formData.append('file', next.file);
    this.current = this.http
      .post<UploadedFile>('/api/v1/files/upload', formData, {
        reportProgress: true,
        observe: 'events',
        withCredentials: true,
      })
      .subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            this.patch(next.id, { progress: Math.round((100 * event.loaded) / event.total) });
          } else if (event.type === HttpEventType.Response) {
            const body: Partial<UploadedFile> = event.body ?? {};
            const taskFile: TaskFile = {
              fileId: body.id ?? '',
              fileName: body.originalName || next.file.name,
              sizeBytes: body.sizeBytes || next.file.size,
              mimeType: body.mimeType || next.file.type,
              createdAt: body.createdAt || new Date().toISOString(),
            };
            this.finish(next.id);
            this.fileAttached.emit(taskFile);
            this.toast.success(this.uiI18n.translate('ui.file_upload.uploaded_named', { name: taskFile.fileName }));
          }
        },
        error: (err) => {
          const msg =
            err.error?.detail || err.error?.message || this.uiI18n.translate('ui.file_upload.oshibka_zagruzki_fayla');
          this.current = null;
          // The failure is an alert on the file's own row, beside its retry button. Not a toast: inside a modal
          // dialog the rest of the page is aria-hidden, so a toast would never be announced there, and it fades.
          this.patch(next.id, { status: 'failed', error: msg });
          this.pump();
        },
      });
  }

  private finish(id: number) {
    this.current = null;
    this.queue.update((queue) => queue.filter((entry) => entry.id !== id));
    this.pump();
  }

  private patch(id: number, changes: Partial<QueuedUpload>) {
    this.queue.update((queue) => queue.map((entry) => (entry.id === id ? { ...entry, ...changes } : entry)));
  }
}
