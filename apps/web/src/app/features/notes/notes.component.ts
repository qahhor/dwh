import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, debounceTime, distinctUntilChanged, finalize, map, of, tap } from 'rxjs';
import { KeysetPage } from '@core/models/common.models';
import { ListQuery, QueryCondition } from '@core/models/query-meta.models';
import { FormMetaService, canDo, hasCapability } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { ToastService } from '@core/services/toast.service';
import { SMTEntityToolbarComponent } from '@shared/entity/smt-entity-toolbar.component';
import { ListViewState, ListViewsApi } from '@shared/list-views/list-views';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { SMTTabBarComponent, SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { NoteCardComponent } from './note-card.component';
import { NoteFormDialogComponent } from './note-form-dialog.component';
import { NOTE_ENTITY, Note, NotesApi } from './notes.api';

type NoteTab = 'all' | 'pinned';

/** The pinned tab is this filter, so a saved view keeps it. */
const PINNED: QueryCondition = { field: 'isPinned', op: 'eq', value: true };

/** The notes on screen: the first page and the pages added below it. */
interface NoteList {
  items: Note[];
  nextCursor: string | null;
  total: number;
}

/**
 * The reference screen of an entity declared on the server (roadmap items 51–56, plan item 2.1):
 * the form, the list metadata and the first page are resources, the requests go through a typed
 * data service, and the cards, the form and the confirmation are their own components.
 */
@Component({
  selector: 'app-notes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    NoteCardComponent,
    NoteFormDialogComponent,
    SMTButtonComponent,
    SMTEntityToolbarComponent,
    SMTInputComponent,
    SMTTabBarComponent,
    TranslatePipe,
  ],
  templateUrl: './notes.component.html',
  styleUrl: './notes.component.css',
})
export class NotesComponent {
  private readonly notesApi = inject(NotesApi);
  private readonly formMeta = inject(FormMetaService);
  private readonly queryMeta = inject(QueryMetaService);
  private readonly modal = inject(SMTModalService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  readonly search = signal('');
  /** A failed reload keeps the notes on screen. */
  readonly list = linkedSignal<KeysetPage<Note> | null | undefined, NoteList>({
    source: () => this.firstPage.value(),
    computation: (page, previous) =>
      page
        ? {
            items: page.items ?? [],
            nextCursor: page.nextCursor ?? null,
            total: page.totalEstimated ?? page.items?.length ?? 0,
          }
        : (previous?.value ?? { items: [], nextCursor: null, total: 0 }),
  });
  readonly loadingMore = signal(false);

  /** The notes chosen for a bulk action. */
  readonly selectedIds = signal<number[]>([]);
  /** The note in the form dialog: 'new' creates one, null means the dialog is closed. */
  readonly editing = signal<Note | 'new' | null>(null);

  readonly meta = computed(() => this.form.value() ?? null);
  readonly listMeta = computed(() => this.listFields.value() ?? null);

  readonly canCreate = computed(() => canDo(this.meta(), 'create'));
  /** Choosing notes is offered when the note entity has bulk actions the viewer may take. */
  readonly canSelect = computed(() => hasCapability(this.meta(), 'bulk') && canDo(this.meta(), 'delete'));
  readonly activeTab = computed<NoteTab>(() => (this.views.filter().some(isPinnedFilter) ? 'pinned' : 'all'));
  /** Translated again when the language changes: translate() reads the active catalog. */
  readonly tabs = computed<SMTTabItem<NoteTab>[]>(() => [
    { value: 'all', label: this.i18n.translate('notes.tab.all') },
    { value: 'pinned', label: this.i18n.translate('notes.tab.pinned') },
  ]);
  /** What the list asks for; nothing until the default view is applied. */
  private readonly query = computed<ListQuery | undefined>(() =>
    this.viewsLoaded()
      ? { search: this.searched(), sort: this.views.sort(), conditions: this.views.filter(), match: this.views.match() }
      : undefined,
  );

  /** The note form from the server; the buttons follow its actions. */
  private readonly form = rxResource({
    stream: () => this.formMeta.get(NOTE_ENTITY).pipe(catchError(() => this.loadFailed(null))),
  });
  /** Field metadata of the note list, for the export's columns. */
  private readonly listFields = rxResource({
    stream: () => this.queryMeta.get(NOTE_ENTITY).pipe(catchError(() => of(null))),
  });

  /** Saved views of the note list: the pinned tab and the order travel with a view. */
  readonly views = new ListViewState(NOTE_ENTITY, inject(ListViewsApi), {
    defaultSort: () => null,
    onApply: () => this.firstPage.reload(),
  });
  private readonly viewsLoaded = toSignal(this.views.load().pipe(map(() => true)), { initialValue: false });
  private readonly searched = toSignal(toObservable(this.search).pipe(debounceTime(300), distinctUntilChanged()), {
    initialValue: '',
  });
  readonly firstPage = rxResource({
    params: this.query,
    stream: ({ params }) => this.notesApi.page(params).pipe(catchError(() => this.loadFailed(null))),
  });

  setTab(tab: NoteTab): void {
    const others = this.views.filter().filter((condition) => !isPinnedFilter(condition));
    this.views.filter.set(tab === 'pinned' ? [...others, PINNED] : others);
  }

  /** The next page, added below the notes on screen. */
  loadMore(): void {
    const cursor = this.list().nextCursor;
    const query = this.query();
    if (!cursor || !query || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.notesApi
      .page(query, cursor)
      .pipe(finalize(() => this.loadingMore.set(false)))
      .subscribe({
        next: (page) =>
          this.list.update((list) => ({
            ...list,
            items: [...list.items, ...(page.items ?? [])],
            nextCursor: page.nextCursor ?? null,
          })),
        error: () => this.loadFailed(null),
      });
  }

  isSelected(note: Note): boolean {
    return this.selectedIds().includes(note.id);
  }

  setSelected(note: Note, selected: boolean): void {
    this.selectedIds.update((ids) => [...ids.filter((id) => id !== note.id), ...(selected ? [note.id] : [])]);
  }

  saved(): void {
    this.editing.set(null);
    this.firstPage.reload();
  }

  togglePin(note: Note): void {
    this.notesApi.togglePin(note.id).subscribe({
      next: () => this.firstPage.reload(),
      error: () => this.toast.error(this.i18n.translate('notes.pin_error')),
    });
  }

  /** Asks first; the dialog stays open while the note is deleted and shows the failure. */
  remove(note: Note): void {
    const t = (key: string, params?: Record<string, string>) => this.i18n.translate(key, params);
    this.modal
      .confirm({
        title: t('common.delete'),
        message: t('notes.delete_confirm', { title: note.title }),
        yesLabel: t('common.delete'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () =>
          this.notesApi.remove(note.id).pipe(
            tap(() => {
              this.toast.success(t('notes.deleted'));
              this.firstPage.reload();
            }),
          ),
        actionError: () => t('notes.delete_error'),
      })
      .subscribe();
  }

  private loadFailed<T>(value: T) {
    this.toast.error(this.i18n.translate('notes.load_error'));
    return of(value);
  }
}

function isPinnedFilter(condition: QueryCondition): boolean {
  return condition.field === PINNED.field && condition.op === PINNED.op && condition.value === true;
}
