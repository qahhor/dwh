import { Component, EventEmitter, Input, Output, Signal, TemplateRef, computed, inject, input, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FileDetail } from '../files.models';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { QueryListMeta } from '../../../core/models/query-meta.models';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { ListViewState } from '../../../shared/list-views/list-views';
import { UiServerTableComponent } from '../../../shared/ui/ui-server-table.component';
import { registryTableConfig } from '../../../shared/ui/registry-table-config';
import { OrderBy, TableConfig } from '../../../shared/ui-kit/components/table/table.types';

/**
 * The file list, a page at a time from the server, on the registry table
 * (`query-meta/mf.files`): columns, sorting of the whole list, column
 * settings, saved views and the filter come from the server's field list.
 * Downloading and deleting stay on each row; the region and its buttons keep
 * the names the end-to-end suite and screen readers rely on.
 */
@Component({
  selector: 'app-files-table',
  standalone: true,
  imports: [
    CommonModule,
    TranslatePipe,
    UiServerTableComponent
  ],
  template: `
    <div class="table-container" role="region" [attr.aria-label]="'files.tablica_faylov' | t" tabindex="0" [attr.aria-busy]="pager().loading()">
      @if (config(); as config) {
        <ui-server-table
          [pager]="pager()"
          [config]="config"
          [views]="views()"
          [filterMeta]="meta()"
          [exportable]="true"
          [exportSearch]="exportSearch()"
          [exportOptions]="exportOptions()"
          [lockedColumns]="['originalName', 'actions']"
          [loadingLabel]="'files.list_loading' | t"
          [errorLabel]="'files.list_load_error' | t"
          [emptyTemplate]="emptyState"
          (sortChange)="sortChange.emit($event)" />
      }
    </div>

    <ng-template #nameCell let-file>
      <div class="name-with-icon">
        <div class="file-icon-wrapper" [ngClass]="getFileCategory(file.mimeType, file.originalName)">
          <span class="material-symbols-outlined" aria-hidden="true">{{ getFileIcon(file.mimeType, file.originalName) }}</span>
        </div>
        <button type="button" class="file-name-cell" (click)="download.emit(file)" [attr.aria-label]="'files.download_named' | t:{name: file.originalName}" [title]="'files.skachat_fayl' | t">
          <span class="primary-name">{{ file.originalName }}</span>
          <span class="sha-sub text-muted text-xs font-mono">{{ file.sha256.substring(0, 12) }}...</span>
        </button>
      </div>
    </ng-template>
    <ng-template #sizeCell let-file><span class="size-pill font-mono">{{ formatBytes(file.sizeBytes) }}</span></ng-template>
    <ng-template #mimeCell let-file><span class="mime-badge">{{ file.mimeType }}</span></ng-template>
    <ng-template #creatorCell let-file>
      @if (file.creatorName) {
        <div class="creator-cell">
          <span class="creator-name">{{ file.creatorName }}</span>
          <span class="creator-login text-muted text-xs">&#64;{{ file.creatorLogin }}</span>
        </div>
      } @else {
        <span class="text-muted">—</span>
      }
    </ng-template>
    <ng-template #dateCell let-file><span class="date-cell tabular-nums">{{ file.createdAt | date:'dd.MM.yyyy HH:mm' }}</span></ng-template>
    <ng-template #actionsCell let-file>
      <div class="row-actions">
        <button type="button" class="action-btn download-btn" [attr.aria-label]="'files.download_named' | t:{name: file.originalName}" (click)="download.emit(file)" [title]="'files.skachat' | t">
          <span class="material-symbols-outlined" aria-hidden="true">download</span>
        </button>
        @if (canDeleteFn(file)) {
          <button
            type="button"
            class="action-btn delete-btn"
            [disabled]="isDeleting"
            [attr.aria-label]="'files.delete_named' | t:{name: file.originalName}"
            (click)="delete.emit(file)"
            [title]="'common.delete' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">delete</span>
          </button>
        }
      </div>
    </ng-template>
    <ng-template #emptyState>
      <div class="empty-state-box">
        <span class="material-symbols-outlined empty-icon" aria-hidden="true">folder_open</span>
        <h3>{{ 'files.fayly_ne_naydeny' | t }}</h3>
        <p>{{ 'files.zagruzite_pervyy_fayl_s_pomoschyu_knopki_zagruzi' | t }}</p>
      </div>
    </ng-template>
  `,
  styles: [`
    :host {
      display: block;
    }

    .table-container {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      overflow-x: auto;
    }

    .name-with-icon { display: flex; align-items: center; gap: 10px; min-width: 0; }

    .file-icon-wrapper {
      width: 32px;
      height: 32px;
      border-radius: 8px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 18px;
    }

    .file-icon-wrapper.image { background: var(--success-bg); color: var(--success-text); }
    .file-icon-wrapper.pdf { background: var(--danger-bg); color: var(--danger-text); }
    .file-icon-wrapper.doc { background: var(--info-bg); color: var(--info-text); }
    .file-icon-wrapper.sheet { background: var(--success-bg); color: var(--success-text); }
    .file-icon-wrapper.archive { background: var(--warning-bg); color: var(--warning-text); }
    .file-icon-wrapper.other { background: var(--bg-hover); color: var(--text-muted); }

    .file-name-cell {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;
      background: transparent;
      border: none;
      padding: 0;
      cursor: pointer;
      text-align: left;
    }

    .file-name-cell:hover .primary-name {
      color: var(--primary);
      text-decoration: underline;
    }

    .primary-name {
      font-weight: 600;
      color: var(--text-main);
      font-size: 13px;
    }

    .sha-sub {
      font-size: 11px;
      color: var(--text-light);
    }

    .size-pill {
      font-size: 12px;
      color: var(--text-muted);
    }

    .mime-badge {
      font-size: 11px;
      font-family: monospace;
      padding: 2px 6px;
      border-radius: 4px;
      background: var(--bg-hover);
      color: var(--text-muted);
    }

    .creator-cell {
      display: flex;
      flex-direction: column;
    }

    .creator-name {
      font-size: 13px;
      color: var(--text-main);
    }

    .date-cell {
      font-size: 12px;
      color: var(--text-light);
    }

    .row-actions {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 6px;
    }

    .action-btn {
      width: 28px;
      height: 28px;
      border-radius: 4px;
      border: none;
      background: transparent;
      color: var(--text-light);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .action-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .action-btn.download-btn:hover {
      background: var(--primary-subtle);
      color: var(--primary-text);
    }

    .action-btn.delete-btn:hover {
      background: var(--danger-bg);
      color: var(--danger-text);
    }

    .empty-state-cell {
      padding: 48px !important;
      text-align: center;
    }

    .empty-state-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }

    .empty-icon {
      font-size: 48px;
      color: var(--text-light);
    }

    .empty-state-box h3 {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }

    .empty-state-box p {
      font-size: 13px;
      color: var(--text-light);
      margin: 0;
    }
  `]
})
export class FilesTableComponent {
  readonly pager = input.required<KeysetPager<FileDetail>>();
  readonly meta = input<QueryListMeta | null>(null);
  readonly views = input<ListViewState | null>(null);
  /** The search text and scope on screen, so an export matches the list shown. */
  readonly exportSearch = input<string | null>(null);
  readonly exportOptions = input<Record<string, string> | null>(null);
  @Input() isDeleting: boolean = false;
  @Input() canDeleteFn: (file: FileDetail) => boolean = () => false;

  @Output() download = new EventEmitter<FileDetail>();
  @Output() delete = new EventEmitter<FileDetail>();
  @Output() sortChange = new EventEmitter<{ column: string; sortBy: OrderBy } | undefined>();

  private readonly i18n = inject(I18nService);
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly sizeCell = viewChild.required<TemplateRef<unknown>>('sizeCell');
  private readonly mimeCell = viewChild.required<TemplateRef<unknown>>('mimeCell');
  private readonly creatorCell = viewChild.required<TemplateRef<unknown>>('creatorCell');
  private readonly dateCell = viewChild.required<TemplateRef<unknown>>('dateCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  /** Registry columns plus the row actions, which are not a field. */
  readonly config = computed<TableConfig<FileDetail> | null>(() => {
    const meta = this.meta();
    const views = this.views();
    if (!meta) return null;
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const base = registryTableConfig<FileDetail>(meta, {
      translate: key => this.i18n.translate(key),
      trackBy: (_index, file) => file.id,
      ariaLabel: this.i18n.translate('files.spisok_faylov'),
      sort: views?.sort() ?? null,
      cells: {
        originalName: cell(this.nameCell),
        sizeBytes: cell(this.sizeCell),
        mimeType: cell(this.mimeCell),
        creatorName: cell(this.creatorCell),
        createdAt: cell(this.dateCell)
      },
      widths: { sizeBytes: '110px', createdAt: '150px' }
    });
    return {
      ...base,
      columns: {
        ...base.columns,
        actions: {
          key: 'actions',
          header: { type: 'primitive', value: this.i18n.translate('common.actions') },
          content: cell(this.actionsCell),
          width: '110px',
          align: 'right'
        }
      },
      columnsOrder: [...base.columnsOrder, 'actions']
    };
  });

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
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }
}
