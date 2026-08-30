import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpEventType } from '@angular/common/http';
import { TaskFile } from '../../core/models/task.models';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'ui-file-upload',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="file-upload-wrapper">
      <!-- Drag & Drop Upload Zone -->
      <label
        *ngIf="canUpload"
        class="drop-zone"
        [for]="fileInputId"
        [class.dragging]="isDragging()"
        (dragover)="onDragOver($event)"
        (dragleave)="onDragLeave($event)"
        (drop)="onDrop($event)"
      >
        <input
          [id]="fileInputId"
          type="file"
          [multiple]="multiple"
          class="sr-only"
          (change)="onFilesSelected($event)"
        />
        <div class="drop-content">
          <span class="material-symbols-outlined drop-icon" aria-hidden="true">cloud_upload</span>
          <div class="drop-text">
            <span class="primary-text">Перетащите файлы сюда или <strong>нажмите для выбора</strong></span>
            <span class="sub-text">До 50 МБ на файл (PDF, PNG, JPG, DOCX, ZIP и др.)</span>
          </div>
        </div>
      </label>

      <!-- Upload Progress Indicator -->
      <div
        *ngIf="isUploading()"
        class="upload-progress-bar"
        role="progressbar"
        aria-label="Загрузка файлов"
        aria-valuemin="0"
        aria-valuemax="100"
        [attr.aria-valuenow]="uploadProgress()"
        [attr.aria-valuetext]="uploadProgress() + '%'"
      >
        <div class="progress-track">
          <div class="progress-fill" [style.width.%]="uploadProgress()"></div>
        </div>
        <span class="progress-label">Загрузка... {{ uploadProgress() }}%</span>
      </div>

      <!-- File Attachment List -->
      <div class="attachments-list" *ngIf="files && files.length > 0" role="list" aria-label="Прикреплённые файлы">
        <div *ngFor="let file of files" class="file-card" role="listitem">
          <div class="file-type-icon" [ngClass]="getFileCategory(file.mimeType, file.fileName)">
            <span class="material-symbols-outlined" aria-hidden="true">{{ getFileIcon(file.mimeType, file.fileName) }}</span>
          </div>
          <button type="button" class="file-info" (click)="downloadFile(file)" [attr.aria-label]="'Скачать ' + file.fileName" title="Скачать файл">
            <span class="file-name">{{ file.fileName }}</span>
            <span class="file-size">{{ formatBytes(file.sizeBytes) }}</span>
          </button>
          <div class="file-actions">
            <button type="button" class="action-btn download" (click)="downloadFile(file)" [attr.aria-label]="'Скачать ' + file.fileName" title="Скачать">
              <span class="material-symbols-outlined" aria-hidden="true">download</span>
            </button>
            <button
              *ngIf="canDelete"
              type="button"
              class="action-btn delete"
              (click)="removeFile(file, $event)"
              [attr.aria-label]="'Удалить ' + file.fileName"
              title="Удалить вложение"
            >
              <span class="material-symbols-outlined" aria-hidden="true">delete</span>
            </button>
          </div>
        </div>
      </div>

      <div *ngIf="(!files || files.length === 0) && !canUpload" class="empty-files">
        <span class="material-symbols-outlined empty-icon" aria-hidden="true">attach_file</span>
        <span>Нет прикрепленных файлов</span>
      </div>
    </div>
  `,
  styles: [`
    .file-upload-wrapper {
      display: flex;
      flex-direction: column;
      gap: 12px;
      width: 100%;
    }

    .drop-zone {
      border: 2px dashed rgba(255, 255, 255, 0.15);
      border-radius: 10px;
      padding: 20px;
      text-align: center;
      cursor: pointer;
      background: rgba(255, 255, 255, 0.02);
      transition: all 0.2s ease;
      user-select: none;
    }

    .drop-zone:hover, .drop-zone.dragging {
      border-color: var(--color-primary, #6366f1);
      background: rgba(99, 102, 241, 0.06);
    }

    .drop-content {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }

    .drop-icon {
      font-size: 32px;
      color: var(--color-primary, #6366f1);
    }

    .drop-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .primary-text {
      font-size: 13px;
      color: var(--text-primary, #f1f5f9);
    }

    .primary-text strong {
      color: var(--color-primary, #818cf8);
    }

    .sub-text {
      font-size: 11px;
      color: var(--text-secondary, #94a3b8);
    }

    /* Progress bar */
    .upload-progress-bar {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .progress-track {
      flex: 1;
      height: 6px;
      background: rgba(255, 255, 255, 0.1);
      border-radius: 3px;
      overflow: hidden;
    }

    .progress-fill {
      height: 100%;
      background: var(--color-primary, #6366f1);
      transition: width 0.15s ease;
    }

    .progress-label {
      font-size: 12px;
      color: var(--text-secondary, #94a3b8);
    }

    /* Attachments list */
    .attachments-list {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 8px;
    }

    .file-card {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px;
      background: rgba(255, 255, 255, 0.04);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 8px;
      transition: background 0.15s ease;
    }

    .file-card:hover {
      background: rgba(255, 255, 255, 0.08);
    }

    .file-type-icon {
      width: 32px;
      height: 32px;
      border-radius: 6px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 18px;
      flex-shrink: 0;
    }

    .file-type-icon.image { background: rgba(56, 189, 248, 0.15); color: #38bdf8; }
    .file-type-icon.pdf { background: rgba(248, 113, 113, 0.15); color: #f87171; }
    .file-type-icon.doc { background: rgba(96, 165, 250, 0.15); color: #60a5fa; }
    .file-type-icon.sheet { background: rgba(52, 211, 153, 0.15); color: #34d399; }
    .file-type-icon.archive { background: rgba(251, 191, 36, 0.15); color: #fbbf24; }
    .file-type-icon.other { background: rgba(148, 163, 184, 0.15); color: #94a3b8; }

    .file-info {
      flex: 1;
      min-width: 0;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      border: 0;
      padding: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      text-align: left;
    }

    .file-name {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-primary, #f1f5f9);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .file-size {
      font-size: 11px;
      color: var(--text-secondary, #94a3b8);
    }

    .file-actions {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .action-btn {
      width: 28px;
      height: 28px;
      border-radius: 4px;
      border: none;
      background: transparent;
      color: #94a3b8;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .action-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .action-btn.download:hover {
      background: rgba(99, 102, 241, 0.15);
      color: #818cf8;
    }

    .action-btn.delete:hover {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
    }

    .empty-files {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: #64748b;
      padding: 8px 0;
    }

    .empty-icon {
      font-size: 18px;
    }
  `]
})
export class UiFileUploadComponent {
  private static nextId = 0;

  @Input() files: TaskFile[] = [];
  @Input() canUpload = true;
  @Input() canDelete = true;
  @Input() multiple = true;

  @Output() fileAttached = new EventEmitter<TaskFile>();
  @Output() fileRemoved = new EventEmitter<TaskFile>();

  readonly isDragging = signal<boolean>(false);
  readonly isUploading = signal<boolean>(false);
  readonly uploadProgress = signal<number>(0);
  readonly fileInputId = `ui-file-upload-${UiFileUploadComponent.nextId++}`;

  constructor(
    private http: HttpClient,
    private toast: ToastService
  ) {}

  onDragOver(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(true);
  }

  onDragLeave(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(false);
  }

  onDrop(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(false);

    if (event.dataTransfer && event.dataTransfer.files.length > 0) {
      this.uploadFiles(Array.from(event.dataTransfer.files));
    }
  }

  onFilesSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.uploadFiles(Array.from(input.files));
      input.value = '';
    }
  }

  uploadFiles(filesToUpload: File[]) {
    for (const file of filesToUpload) {
      this.uploadSingleFile(file);
    }
  }

  private uploadSingleFile(file: File) {
    const formData = new FormData();
    formData.append('file', file);

    this.isUploading.set(true);
    this.uploadProgress.set(0);

    this.http.post<any>('/api/v1/files/upload', formData, {
      reportProgress: true,
      observe: 'events',
      withCredentials: true
    }).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.uploadProgress.set(Math.round((100 * event.loaded) / event.total));
        } else if (event.type === HttpEventType.Response) {
          this.isUploading.set(false);
          const body = event.body;
          const taskFile: TaskFile = {
            fileId: body.id,
            fileName: body.originalName || file.name,
            sizeBytes: body.sizeBytes || file.size,
            mimeType: body.mimeType || file.type,
            createdAt: body.createdAt || new Date().toISOString()
          };
          this.fileAttached.emit(taskFile);
          this.toast.success(`Файл ${taskFile.fileName} успешно загружен`);
        }
      },
      error: (err) => {
        this.isUploading.set(false);
        const msg = err.error?.detail || err.error?.message || 'Ошибка загрузки файла';
        this.toast.error(msg, 'Загрузка не удалась');
      }
    });
  }

  downloadFile(file: TaskFile) {
    window.open(`/api/v1/files/${file.fileId}/download`, '_blank', 'noopener,noreferrer');
  }

  removeFile(file: TaskFile, event: Event) {
    event.stopPropagation();
    this.fileRemoved.emit(file);
  }

  getFileCategory(mimeType?: string, fileName?: string): string {
    const mime = (mimeType || '').toLowerCase();
    const name = (fileName || '').toLowerCase();

    if (mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg)$/.test(name)) return 'image';
    if (mime === 'application/pdf' || name.endsWith('.pdf')) return 'pdf';
    if (/\.(docx?|odt|rtf|txt|md)$/.test(name)) return 'doc';
    if (/\.(xlsx?|csv|ods)$/.test(name)) return 'sheet';
    if (/\.(zip|tar|gz|rar|7z)$/.test(name)) return 'archive';
    return 'other';
  }

  getFileIcon(mimeType?: string, fileName?: string): string {
    const cat = this.getFileCategory(mimeType, fileName);
    switch (cat) {
      case 'image': return 'image';
      case 'pdf': return 'picture_as_pdf';
      case 'doc': return 'description';
      case 'sheet': return 'table_chart';
      case 'archive': return 'folder_zip';
      default: return 'attach_file';
    }
  }

  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }
}
