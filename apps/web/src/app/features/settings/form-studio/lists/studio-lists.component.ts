import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  Signal,
  signal,
  TemplateRef,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { isRevisionConflict, SaveErrorNotifier } from '@shared/ui/save-errors';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import {
  emptyStudioListEntry,
  STUDIO_LIST_ENTRY_FIELDS,
  StudioListEntryDialogComponent,
  studioListEntryField,
  type StudioListEntryForm,
} from './studio-list-entry-dialog.component';
import {
  StudioListsStore,
  type StudioListEntry,
  type StudioListItem,
  type StudioListSummary,
} from './studio-lists.store';

/** What the open dialog edits: a new or renamed list, a new or changed item. */
type Editing =
  | { kind: 'list'; list: StudioListSummary | null }
  | { kind: 'item'; list: StudioListSummary; item: StudioListItem | null };

/**
 * The reference lists page: the lists an administrator keeps without a release (ADR-0041). A list opens with its items;
 * an item is added, renamed, reordered or deactivated — never deleted, since records keep its code.
 */
@Component({
  selector: 'app-studio-lists',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    RouterLink,
    TranslatePipe,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTTagComponent,
    UiLocalTableComponent,
    UiPageHeaderComponent,
    StudioListEntryDialogComponent,
  ],
  providers: [StudioListsStore],
  templateUrl: './studio-lists.component.html',
  styleUrl: './studio-lists.component.css',
})
export class StudioListsComponent implements OnInit {
  readonly store = inject(StudioListsStore);
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  private readonly listNameCell = viewChild.required<TemplateRef<unknown>>('listNameCell');
  private readonly countCell = viewChild.required<TemplateRef<unknown>>('countCell');
  private readonly modifiedCell = viewChild.required<TemplateRef<unknown>>('modifiedCell');
  private readonly listActionsCell = viewChild.required<TemplateRef<unknown>>('listActionsCell');
  private readonly itemNameCell = viewChild.required<TemplateRef<unknown>>('itemNameCell');
  private readonly itemStateCell = viewChild.required<TemplateRef<unknown>>('itemStateCell');
  private readonly itemActionsCell = viewChild.required<TemplateRef<unknown>>('itemActionsCell');

  readonly editing = signal<Editing | null>(null);
  readonly initial = signal<StudioListEntryForm>(emptyStudioListEntry(1));
  readonly saving = signal(false);
  readonly serverErrors = signal<Readonly<Record<string, string>>>({});
  readonly otherErrors = signal<readonly string[]>([]);
  /** A new list or item takes a code; an existing one keeps it. */
  readonly creating = computed(() => {
    const editing = this.editing();
    return !editing || (editing.kind === 'list' ? !editing.list : !editing.item);
  });

  readonly listsConfig = computed<TableConfig<StudioListSummary>>(() => {
    this.i18n.currentLang();
    return {
      trackBy: (_index, list) => list.code,
      ariaLabel: this.i18n.translate('studio.lists.title'),
      layout: 'fit',
      columns: {
        name: { header: this.header('studio.lists.column.name'), content: this.cell(this.listNameCell) },
        items: { header: this.header('studio.lists.column.items'), content: this.cell(this.countCell), width: '140px' },
        modifiedAt: { header: this.header('studio.lists.column.modified'), content: this.cell(this.modifiedCell) },
        actions: { header: this.header('common.actions'), content: this.cell(this.listActionsCell), align: 'right' },
      },
      columnsOrder: ['name', 'items', 'modifiedAt', 'actions'],
    };
  });
  readonly itemsConfig = computed<TableConfig<StudioListItem>>(() => {
    this.i18n.currentLang();
    return {
      trackBy: (_index, item) => item.code,
      ariaLabel: this.i18n.translate('studio.lists.items'),
      layout: 'fit',
      columns: {
        name: { header: this.header('studio.lists.column.value'), content: this.cell(this.itemNameCell) },
        sortOrder: {
          header: this.header('studio.lists.order'),
          content: { type: 'primitive', value: (item: StudioListItem) => String(item.sortOrder) },
          width: '110px',
        },
        active: { header: this.header('studio.lists.column.state'), content: this.cell(this.itemStateCell) },
        actions: { header: this.header('common.actions'), content: this.cell(this.itemActionsCell), align: 'right' },
      },
      columnsOrder: ['name', 'sortOrder', 'active', 'actions'],
    };
  });
  readonly listSortValues = {
    name: (list: StudioListSummary) => this.store.name(list.name),
    items: (list: StudioListSummary) => list.items,
    modifiedAt: (list: StudioListSummary) => list.modifiedAt,
  };
  readonly itemSortValues = {
    name: (item: StudioListItem) => this.store.name(item.name),
    sortOrder: (item: StudioListItem) => item.sortOrder,
    active: (item: StudioListItem) => (item.active ? 1 : 0),
  };

  ngOnInit(): void {
    this.store.load();
  }

  newList(): void {
    this.edit({ kind: 'list', list: null }, emptyStudioListEntry(1));
  }

  renameList(list: StudioListSummary): void {
    this.edit({ kind: 'list', list }, { ...emptyStudioListEntry(1), code: list.code, ...list.name });
  }

  newItem(list: StudioListSummary): void {
    const items = this.store.open()?.items ?? [];
    const next = Math.min(9999, items.reduce((most, item) => Math.max(most, item.sortOrder), 0) + 10);
    this.edit({ kind: 'item', list, item: null }, emptyStudioListEntry(next));
  }

  editItem(list: StudioListSummary, item: StudioListItem): void {
    this.edit(
      { kind: 'item', list, item },
      { code: item.code, ...item.name, sortOrder: item.sortOrder, active: item.active },
    );
  }

  close(): void {
    this.editing.set(null);
  }

  fieldEdited(field: string): void {
    this.serverErrors.update(({ [field]: _gone, ...rest }) => rest);
  }

  save(entry: StudioListEntry): void {
    const editing = this.editing();
    if (!editing || this.saving()) return;
    this.saving.set(true);
    this.serverErrors.set({});
    this.otherErrors.set([]);
    const request: Observable<unknown> =
      editing.kind === 'list'
        ? editing.list
          ? this.store.renameList(editing.list, entry)
          : this.store.createList(entry)
        : editing.item
          ? this.store.updateItem(editing.list, editing.item, entry)
          : this.store.addItem(editing.list, entry);
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.editing.set(null);
        if (editing.kind === 'list') this.store.listSaved(saved as Parameters<StudioListsStore['listSaved']>[0]);
        else this.store.itemSaved();
        this.toast.success(this.i18n.translate('studio.lists.saved'));
      },
      error: (error) => {
        this.saving.set(false);
        this.refused(error);
      },
    });
  }

  private refused(error: unknown): void {
    const status = (error as { status?: number })?.status;
    if (status === 422 || (status === 409 && !isRevisionConflict(error))) {
      const problem = problemFieldErrors(error);
      const fields: Record<string, string> = {};
      const other = [...problem.other];
      for (const [field, message] of Object.entries(problem.fields)) {
        const own = studioListEntryField(field);
        if ((STUDIO_LIST_ENTRY_FIELDS as readonly string[]).includes(own)) fields[own] = message;
        else other.push(message);
      }
      if (status === 409 && Object.keys(fields).length === 0)
        fields['code'] = problemText(error) || this.i18n.translate('studio.lists.save_failed');
      this.serverErrors.set(fields);
      this.otherErrors.set(other);
      return;
    }
    this.saveErrors.show(error, {
      fallbackKey: 'studio.lists.save_failed',
      reload: () => {
        this.editing.set(null);
        this.store.load();
        const open = this.store.open();
        if (open) this.store.select(open.list.code);
      },
    });
  }

  private edit(editing: Editing, initial: StudioListEntryForm): void {
    this.serverErrors.set({});
    this.otherErrors.set([]);
    this.initial.set(initial);
    this.editing.set(editing);
  }

  private header(key: string) {
    return { type: 'primitive' as const, value: this.i18n.translate(key) };
  }

  private cell(template: Signal<TemplateRef<unknown>>) {
    return { type: 'templateRef' as const, value: template };
  }
}
