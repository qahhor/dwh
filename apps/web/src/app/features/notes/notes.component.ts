import { Component, OnInit, OnDestroy, signal, computed, inject, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, finalize } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/services/api.service';
import { KeysetPage } from '../../core/models/common.models';
import { QueryMetaService, toQueryParams } from '../../core/services/query-meta.service';
import { QueryCondition, QueryListMeta } from '../../core/models/query-meta.models';
import { ProblemDetail } from '../../core/models/common.models';
import { FormMeta, FormProblems, FormValues } from '../../core/models/form-meta.models';
import {
  FormMetaService, canDo, formProblems, hasCapability, recordPayload, recordValues, serverProblems,
} from '../../core/services/form-meta.service';
import { ToastService } from '../../core/services/toast.service';
import { SMTButtonComponent } from '../../shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../shared/ui-kit/components/modal';
import { UiMarkdownViewComponent } from '../../shared/ui/ui-markdown-view.component';
import { SMTEntityFormComponent } from '../../shared/entity/smt-entity-form.component';
import { SMTEntityCardComponent } from '../../shared/entity/smt-entity-card.component';
import { SMTEntityToolbarComponent } from '../../shared/entity/smt-entity-toolbar.component';
import { UiRecordHistoryComponent } from '../../shared/ui/ui-record-history.component';
import { ListViewState, ListViewsApi } from '../../shared/list-views/list-views';
import { SMTCheckboxComponent } from '../../shared/ui-kit/components/forms/checkbox';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';
import { SMTTabBarComponent, SMTTabItem } from '../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent } from '../../shared/ui-kit/components/forms/input';

const NOTES_PAGE_SIZE = 50;

/** The note entity (MsNoteEntity on the server): its form, rules and the viewer's actions. */
const NOTE_ENTITY = 'ms.notes';

/** The pinned tab is this filter, so a saved view keeps it. */
const PINNED: QueryCondition = { field: 'isPinned', op: 'eq', value: true };

/** What a new note starts with. */
const NEW_NOTE = { color: 'default', isPinned: false };

export interface Note {
  id: number;
  title: string;
  contentMd: string;
  color: string;
  isPinned: boolean;
  attributes: Record<string, any>;
  createdBy: number;
  createdAt: string;
  modifiedAt: string;
}

@Component({
  selector: 'app-notes',
  standalone: true,
  imports: [SMTInputComponent,
    SMTTabBarComponent, CommonModule,
    SMTButtonComponent,
    SMTDialogComponent, SMTDialogContentDirective,
    UiMarkdownViewComponent,
    SMTEntityFormComponent,
    SMTEntityCardComponent,
    SMTEntityToolbarComponent,
    UiRecordHistoryComponent,
    SMTCheckboxComponent,
    TranslatePipe
  ],
  template: `
    <div class="notes-view" role="region" [attr.aria-label]="'notes.title' | t">
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'notes.title' | t }}</h1>
          <span class="count-badge">{{ total() }}</span>

          <smt-tab-bar
            class="tabs-bar"
            [tabs]="noteTabs()"
            [value]="activeTab()"
            [smtAriaLabel]="'notes.title' | t"
            (valueChange)="$event && setTab($event)" />
        </div>
        <div class="header-right">
          <smt-entity-toolbar
            [meta]="meta()"
            [views]="views"
            [listMeta]="listMeta()"
            [search]="searchQuery"
            [(selected)]="selectedIds"
            (bulkDone)="loadNotes()" />
          <smt-input
            class="search-box"
            type="search"
            smtIcon="search"
            clearable
            smtSize="sm"
            [placeholder]="'notes.search_placeholder' | t"
            [smtAriaLabel]="'notes.search_placeholder' | t"
            [value]="searchQuery"
            (valueChange)="onSearchChange($any($event) ?? '')" />
          <button smt-button type="button"
            *ngIf="canCreate()"
            smtVariant="primary"
            smtSize="md"
            smtIcon="add"
            [attr.aria-label]="'notes.create' | t"
            (click)="openCreateModal()"
          >
            {{ 'notes.create' | t }}
          </button>
        </div>
      </div>

      <!-- Notes Grid -->
      <div class="notes-grid" *ngIf="filteredNotes().length > 0; else emptyState">
        <div
          *ngFor="let note of filteredNotes()"
          class="note-card"
          [ngClass]="'color-' + (note.color || 'default')"
          [class.is-pinned]="note.isPinned"
        >
          <div class="card-header">
            @if (canSelect()) {
              <div smt-checkbox
                class="note-select"
                smtHideLabel
                [smtAriaLabel]="'notes.select' | t:{ title: note.title }"
                [checked]="isSelected(note)"
                (smtCheckedChange)="setSelected(note, $event)"></div>
            }
            <span class="note-title">{{ note.title }}</span>
            <div class="card-actions">
              <button
                *ngIf="canPin()"
                type="button"
                class="icon-btn"
                [class.pinned]="note.isPinned"
                [attr.aria-label]="note.isPinned ? ('notes.unpin' | t) : ('notes.pin' | t)"
                [attr.aria-pressed]="note.isPinned"
                [title]="note.isPinned ? ('notes.unpin' | t) : ('notes.pin' | t)"
                (click)="togglePin(note)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">{{ note.isPinned ? 'keep' : 'push_pin' }}</span>
              </button>
              <button
                *ngIf="canEdit()"
                type="button"
                class="icon-btn"
                [attr.aria-label]="'common.edit' | t"
                [title]="'common.edit' | t"
                (click)="openEditModal(note)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">edit</span>
              </button>
              <button
                *ngIf="canDelete()"
                type="button"
                class="icon-btn text-danger"
                [attr.aria-label]="'common.delete' | t"
                [title]="'common.delete' | t"
                (click)="deleteNote(note)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">delete</span>
              </button>
            </div>
          </div>

          <div class="card-body">
            <ui-markdown-view class="note-content" [content]="note.contentMd"></ui-markdown-view>
            @if (meta(); as form) {
              <smt-entity-card class="note-custom" [meta]="form" [value]="valuesOf(note)" [sections]="customSections" />
            }
          </div>

          <div class="card-footer">
            <span class="note-date">{{ note.modifiedAt | date:'short' }}</span>
          </div>
        </div>
      </div>
      @if (nextCursor()) {
        <div class="notes-more">
          <button smt-button type="button" smtVariant="secondary" data-testid="notes-load-more" [smtLoading]="isLoadingMore()"
            (click)="loadMore()">{{ 'notes.load_more' | t }}</button>
        </div>
      }

      <ng-template #emptyState>
        <div class="empty-state">
          <span class="material-symbols-outlined empty-icon" aria-hidden="true">description</span>
          <h3>{{ 'notes.empty_title' | t }}</h3>
          <p>{{ 'notes.empty_desc' | t }}</p>
          <button smt-button type="button"
            *ngIf="canCreate()"
            smtVariant="primary"
            smtSize="md"
            smtIcon="add"
            [attr.aria-label]="'notes.create' | t"
            (click)="openCreateModal()"
          >
            {{ 'notes.create' | t }}
          </button>
        </div>
      </ng-template>

      <!-- Modal Create / Edit -->
      <smt-dialog
        [open]="isModalOpen()"
        [smtTitle]="editingNote() ? ('notes.edit_title' | t) : ('notes.create_title' | t)"
        smtSize="md"
        [dismissible]="!isSaving()"
        (closed)="closeModal()">
        <ng-template smtDialogContent>
        <form ngNoForm (submit)="$event.preventDefault(); saveNote()" class="modal-form" novalidate id="noteForm">
          @if (meta(); as form) {
            <smt-entity-form [meta]="form" [(value)]="formValues" [problems]="problems()" [disabled]="isSaving()" />
            @if (showHistory() && editingNote(); as note) {
              <ui-record-history [kind]="form.code" [recordId]="note.id" />
            }
          }
        </form>

        <div footer>
          <div class="modal-footer-actions">
            <button smt-button type="button" smtVariant="secondary" [disabled]="isSaving()" (click)="closeModal()">{{ 'common.cancel' | t }}</button>
            <button smt-button smtVariant="primary" type="submit" form="noteForm" [smtLoading]="isSaving()" (click)="saveNote()">{{ 'common.save' | t }}</button>
          </div>
        </div>
        </ng-template>
      </smt-dialog>

      <!-- Delete Confirmation Modal -->
      <smt-dialog
        [open]="!!deletingNote()"
        [smtTitle]="'common.delete' | t"
        smtSize="sm"
        [dismissible]="!isDeleting()"
        (closed)="cancelDelete()">
        <ng-template smtDialogContent>
        <p class="delete-dialog-text">
          {{ 'notes.delete_confirm' | t:{ title: deletingNote()?.title || '' } }}
        </p>
        <div footer>
          <div class="modal-footer-actions">
            <button smt-button type="button" smtVariant="secondary" [disabled]="isDeleting()" (click)="cancelDelete()">{{ 'common.cancel' | t }}</button>
            <button smt-button type="button" smtVariant="danger" [smtLoading]="isDeleting()" (click)="confirmDelete()">{{ 'common.delete' | t }}</button>
          </div>
        </div>
        </ng-template>
      </smt-dialog>
    </div>
  `,
  styleUrl: './notes.component.css'
})
export class NotesComponent implements OnInit, OnDestroy {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  private api = inject(ApiService);
  private toast = inject(ToastService);
  private formMeta = inject(FormMetaService);
  private queryMeta = inject(QueryMetaService);
  private destroyRef = inject(DestroyRef);
  private readonly uiI18n = inject(I18nService);

  notes = signal<Note[]>([]);
  activeTab = signal<'all' | 'pinned'>('all');
  isModalOpen = signal(false);
  editingNote = signal<Note | null>(null);
  deletingNote = signal<Note | null>(null);
  isSaving = signal(false);
  isDeleting = signal(false);
  /** The note form from the server (roadmap item 55); the buttons follow its actions. */
  readonly meta = signal<FormMeta | null>(null);
  /** The note being edited, by field key. */
  readonly formValues = signal<FormValues>({});
  readonly problems = signal<FormProblems>({});
  /** Field metadata of the note list, for the export's columns. */
  readonly listMeta = signal<QueryListMeta | null>(null);
  /** The notes chosen for a bulk action. */
  readonly selectedIds = signal<number[]>([]);
  /** Registry list ms.notes (roadmap item 51): pinned first, then the latest; a page at a time. */
  readonly total = signal(0);
  readonly nextCursor = signal<string | null>(null);
  readonly isLoadingMore = signal(false);

  canCreate = computed(() => canDo(this.meta(), 'create'));
  canEdit = computed(() => canDo(this.meta(), 'update'));
  canPin = computed(() => canDo(this.meta(), 'pin'));
  canDelete = computed(() => canDo(this.meta(), 'delete'));
  /** Choosing notes is offered when the note entity has bulk actions the viewer may take. */
  canSelect = computed(() => hasCapability(this.meta(), 'bulk') && canDo(this.meta(), 'delete'));
  showHistory = computed(() => hasCapability(this.meta(), 'history'));

  /** The tab filters on the server, so the loaded notes are the ones to show. */
  filteredNotes = computed(() => this.notes());

  /** The card shows the custom fields; the title and text are drawn by the card itself. */
  readonly customSections = ['custom'];

  /** Saved views of the note list (roadmap item 56): the pinned tab and the search order travel with a view. */
  readonly views = new ListViewState(NOTE_ENTITY, inject(ListViewsApi), {
    defaultSort: () => null,
    onApply: () => {
      this.activeTab.set(this.views.filter().some(isPinnedFilter) ? 'pinned' : 'all');
      this.loadNotes();
    }
  });

  private readonly searchSubject = new Subject<string>();
  searchQuery = '';

  private readonly tabsMemo = optionsMemo<SMTTabItem<'all' | 'pinned'>[]>();

  /** A note's values for its card, the same object while the note and the form stay the same. */
  private readonly cardValues = new WeakMap<Note, { meta: FormMeta; values: FormValues }>();

  ngOnInit(): void {
    this.loadForm();
    this.queryMeta.get(NOTE_ENTITY).subscribe({ next: meta => this.listMeta.set(meta), error: () => {} });
    this.views.load().subscribe(() => {
      this.activeTab.set(this.views.filter().some(isPinnedFilter) ? 'pinned' : 'all');
      this.loadNotes();
    });

    this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => this.loadNotes());
  }

  ngOnDestroy(): void {
    this.searchSubject.complete();
  }

  loadNotes(): void {
    this.api.get<KeysetPage<Note>>('/notes', this.listParams(null)).subscribe({
      next: page => {
        this.notes.set(page?.items ?? []);
        this.nextCursor.set(page?.nextCursor ?? null);
        this.total.set(page?.totalEstimated ?? page?.items?.length ?? 0);
      },
      error: () => this.toast.error(this.uiI18n.translate('notes.load_error'))
    });
  }

  /** The next page, added below the notes on screen. */
  loadMore(): void {
    const cursor = this.nextCursor();
    if (!cursor || this.isLoadingMore()) return;
    this.isLoadingMore.set(true);
    this.api.get<KeysetPage<Note>>('/notes', this.listParams(cursor)).pipe(
      finalize(() => this.isLoadingMore.set(false))
    ).subscribe({
      next: page => {
        this.notes.update(notes => [...notes, ...(page?.items ?? [])]);
        this.nextCursor.set(page?.nextCursor ?? null);
      },
      error: () => this.toast.error(this.uiI18n.translate('notes.load_error'))
    });
  }

  setTab(tab: 'all' | 'pinned'): void {
    this.activeTab.set(tab);
    const others = this.views.filter().filter(condition => !isPinnedFilter(condition));
    this.views.filter.set(tab === 'pinned' ? [...others, PINNED] : others);
    this.loadNotes();
  }

  isSelected(note: Note): boolean {
    return this.selectedIds().includes(note.id);
  }

  setSelected(note: Note, selected: boolean): void {
    this.selectedIds.update(ids => selected
      ? (ids.includes(note.id) ? ids : [...ids, note.id])
      : ids.filter(id => id !== note.id));
  }

  loadForm(): void {
    this.formMeta.get(NOTE_ENTITY).subscribe({
      next: meta => this.meta.set(meta),
      error: () => this.toast.error(this.uiI18n.translate('notes.load_error'))
    });
  }

  valuesOf(note: Note): FormValues {
    const meta = this.meta();
    if (!meta) return {};
    const cached = this.cardValues.get(note);
    if (cached?.meta === meta) return cached.values;
    const values = recordValues(meta, note as unknown as Record<string, unknown>);
    this.cardValues.set(note, { meta, values });
    return values;
  }

  onSearchChange(value: string): void {
    this.searchQuery = value;
    this.searchSubject.next(value);
  }

  openCreateModal(): void {
    this.openModal(null);
  }

  openEditModal(note: Note): void {
    this.openModal(note);
  }

  closeModal(): void {
    if (this.isSaving()) return;
    this.isModalOpen.set(false);
  }

  /** Checked by the note's declared rules first; the server checks them again and names the fields it rejects. */
  saveNote(): void {
    const meta = this.meta();
    if (!meta || this.isSaving()) return;
    const translate = (key: string, params?: Record<string, string | number>) => this.uiI18n.translate(key, params);
    const problems = formProblems(meta, this.formValues(), translate);
    this.problems.set(problems);
    if (Object.keys(problems).length > 0) return;

    const current = this.editingNote();
    const payload = recordPayload(meta, this.formValues(), current as unknown as Record<string, unknown> | null);
    const request = current
      ? this.api.put<Note>(`/notes/${current.id}`, payload, { notifyError: false })
      : this.api.post<Note>('/notes', payload, { notifyError: false });
    this.isSaving.set(true);
    request.pipe(
      finalize(() => this.isSaving.set(false))
    ).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate(current ? 'notes.updated' : 'notes.created'));
        this.isSaving.set(false);
        this.isModalOpen.set(false);
        this.loadNotes();
      },
      error: (problem: ProblemDetail) => {
        const onFields = serverProblems(meta, problem?.errors, translate);
        this.problems.set(onFields);
        if (Object.keys(onFields).length === 0) {
          this.toast.error(problem?.status === 422 && problem.detail
            ? problem.detail
            : this.uiI18n.translate(current ? 'notes.save_error' : 'notes.create_error'));
        }
      }
    });
  }

  togglePin(note: Note): void {
    this.api.post<Note>(`/notes/${note.id}/pin`, {}).subscribe({
      next: () => this.loadNotes(),
      error: () => this.toast.error(this.uiI18n.translate('notes.pin_error'))
    });
  }

  deleteNote(note: Note): void {
    this.deletingNote.set(note);
  }

  cancelDelete(): void {
    if (this.isDeleting()) return;
    this.deletingNote.set(null);
  }

  confirmDelete(): void {
    const note = this.deletingNote();
    if (!note) return;

    this.isDeleting.set(true);
    this.api.delete(`/notes/${note.id}`).pipe(
      finalize(() => this.isDeleting.set(false))
    ).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('notes.deleted'));
        this.deletingNote.set(null);
        this.loadNotes();
      },
      error: () => this.toast.error(this.uiI18n.translate('notes.delete_error'))
    });
  }

  noteTabs(): SMTTabItem<'all' | 'pinned'>[] {
    return this.tabsMemo([this.tabText.currentLang()], () => [
      { value: 'all', label: this.tabText.translate('notes.tab.all') },
      { value: 'pinned', label: this.tabText.translate('notes.tab.pinned') },
    ]);
  }

  private openModal(note: Note | null): void {
    const meta = this.meta();
    if (!meta) return;
    this.editingNote.set(note);
    this.problems.set({});
    this.formValues.set(recordValues(meta, (note ?? NEW_NOTE) as unknown as Record<string, unknown>));
    this.isModalOpen.set(true);
  }

  private listParams(cursor: string | null): Record<string, string | number> {
    return {
      limit: NOTES_PAGE_SIZE,
      ...(cursor ? { cursor } : {}),
      ...toQueryParams({
        search: this.searchQuery,
        sort: this.views.sort(),
        conditions: this.views.filter(),
        match: this.views.match()
      })
    };
  }
}

function isPinnedFilter(condition: QueryCondition): boolean {
  return condition.field === PINNED.field && condition.op === PINNED.op && condition.value === true;
}
