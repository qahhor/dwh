import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  input,
  viewChild,
  output,
} from '@angular/core';
import { NgClass, DatePipe } from '@angular/common';
import { FileDetail } from '../files.models';
import { I18nService, LANGUAGE_LOCALES, TranslatePipe } from '@core/services/i18n.service';
import {
  canPreview,
  fileKind,
  fileKindIcon,
  formatFileSize,
  SMTFileKind,
} from '@shared/ui-kit/components/file-preview';
import { QueryListMeta } from '@core/models/query-meta.models';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { ListViewState } from '@shared/list-views/list-views';
import { UiServerTableComponent } from '@shared/ui/ui-server-table.component';
import { registryTableConfig } from '@shared/ui/registry-table-config';
import { OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';

/**
 * The file list, a page at a time from the server, on the registry table
 * (`query-meta/mf.files`): columns, sorting of the whole list, column
 * settings, saved views and the filter come from the server's field list.
 * Downloading and deleting stay on each row; the region and its buttons keep
 * the names the end-to-end suite and screen readers rely on.
 */
@Component({
  selector: 'app-files-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, UiServerTableComponent, DatePipe, NgClass],
  template: `
    <div
      class="table-container"
      role="region"
      [attr.aria-label]="'files.tablica_faylov' | t"
      tabindex="0"
      [attr.aria-busy]="pager().loading()"
    >
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
          (sortChange)="sortChange.emit($event)"
        />
      }
    </div>

    <ng-template #nameCell let-file>
      <div class="name-with-icon">
        <div class="file-icon-wrapper" [ngClass]="kindOf(file)">
          <span class="material-symbols-outlined" aria-hidden="true">{{ iconOf(file) }}</span>
        </div>
        <button
          type="button"
          class="file-name-cell"
          (click)="download.emit(file)"
          [attr.aria-label]="'files.download_named' | t: { name: file.originalName }"
          [title]="'files.skachat_fayl' | t"
        >
          <span class="primary-name">{{ file.originalName }}</span>
        </button>
      </div>
    </ng-template>
    <ng-template #sizeCell let-file
      ><span class="size-pill font-mono">{{ sizeOf(file) }}</span></ng-template
    >
    <ng-template #mimeCell let-file
      ><span class="mime-badge">{{ file.mimeType }}</span></ng-template
    >
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
    <ng-template #dateCell let-file
      ><span class="date-cell tabular-nums">{{ file.createdAt | date: 'dd.MM.yyyy HH:mm' }}</span></ng-template
    >
    <ng-template #actionsCell let-file>
      <div class="row-actions">
        @if (previewable(file)) {
          <button
            type="button"
            class="action-btn"
            data-testid="file-preview"
            [attr.aria-label]="'ui.file.preview' | t: { name: file.originalName }"
            (click)="preview.emit(file)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">visibility</span>
          </button>
        }
        <button
          type="button"
          class="action-btn download-btn"
          [attr.aria-label]="'files.download_named' | t: { name: file.originalName }"
          (click)="download.emit(file)"
          [title]="'files.skachat' | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">download</span>
        </button>
        @if (canDeleteFn()(file)) {
          <button
            type="button"
            class="action-btn delete-btn"
            [disabled]="isDeleting()"
            [attr.aria-label]="'files.delete_named' | t: { name: file.originalName }"
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
  styleUrl: './files-table.component.css',
})
export class FilesTableComponent {
  private readonly i18n = inject(I18nService);

  readonly pager = input.required<KeysetPager<FileDetail>>();

  readonly meta = input<QueryListMeta | null>(null);
  readonly views = input<ListViewState | null>(null);
  /** The search text and scope on screen, so an export matches the list shown. */
  readonly exportSearch = input<string | null>(null);
  readonly exportOptions = input<Record<string, string> | null>(null);

  readonly isDeleting = input<boolean>(false);
  readonly canDeleteFn = input<(file: FileDetail) => boolean>(() => false);

  readonly download = output<FileDetail>();
  /** An image asked to be shown in the preview. */
  readonly preview = output<FileDetail>();
  readonly delete = output<FileDetail>();
  readonly sortChange = output<
    | {
        column: string;
        sortBy: OrderBy;
      }
    | undefined
  >();

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
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, file) => file.id,
      ariaLabel: this.i18n.translate('files.spisok_faylov'),
      sort: views?.sort() ?? null,
      cells: {
        originalName: cell(this.nameCell),
        sizeBytes: cell(this.sizeCell),
        mimeType: cell(this.mimeCell),
        creatorName: cell(this.creatorCell),
        createdAt: cell(this.dateCell),
      },
      widths: { sizeBytes: '110px', createdAt: '150px' },
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
          align: 'right',
        },
      },
      columnsOrder: [...base.columnsOrder, 'actions'],
    };
  });

  kindOf(file: FileDetail): SMTFileKind {
    return fileKind(file.mimeType, file.originalName);
  }

  iconOf(file: FileDetail): string {
    return fileKindIcon(this.kindOf(file));
  }

  previewable(file: FileDetail): boolean {
    return canPreview(file.mimeType, file.originalName);
  }

  sizeOf(file: FileDetail): string {
    return formatFileSize(file.sizeBytes, LANGUAGE_LOCALES[this.i18n.currentLang()] ?? this.i18n.currentLang());
  }
}
