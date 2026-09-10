import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { PermissionService } from '../../core/services/permission.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';

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
  imports: [CommonModule, FormsModule, UiButtonComponent, UiModalComponent, TranslatePipe],
  template: `
    <div class="notes-view">
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'nav.notes' | t }}</h1>
          <span class="count-badge">{{ notes().length }}</span>
        </div>
        <div class="header-right">
          <div class="search-box">
            <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
            <input
              type="text"
              class="search-input"
              [placeholder]="'common.search' | t"
              [(ngModel)]="searchQuery"
              (ngModelChange)="onSearchChange()"
            />
            <button
              *ngIf="searchQuery"
              type="button"
              class="search-clear-btn"
              [attr.aria-label]="'search.clear_query' | t"
              (click)="clearSearch()"
            >
              <span class="material-symbols-outlined" aria-hidden="true">cancel</span>
            </button>
          </div>
          <ui-button
            *ngIf="canCreate()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateModal()"
          >
            {{ 'notes.create' | t }}
          </ui-button>
        </div>
      </div>

      <!-- Notes Grid -->
      <div class="notes-grid" *ngIf="notes().length > 0; else emptyState">
        <div
          *ngFor="let note of notes()"
          class="note-card"
          [ngClass]="'color-' + note.color"
          [class.is-pinned]="note.isPinned"
        >
          <div class="card-header">
            <span class="note-title">{{ note.title }}</span>
            <div class="card-actions">
              <button
                type="button"
                class="icon-btn"
                [class.pinned]="note.isPinned"
                [title]="note.isPinned ? ('notes.unpin' | t) : ('notes.pin' | t)"
                (click)="togglePin(note)"
              >
                <span class="material-symbols-outlined">{{ note.isPinned ? 'keep' : 'push_pin' }}</span>
              </button>
              <button
                *ngIf="canEdit()"
                type="button"
                class="icon-btn"
                [title]="'common.edit' | t"
                (click)="openEditModal(note)"
              >
                <span class="material-symbols-outlined">edit</span>
              </button>
              <button
                *ngIf="canDelete()"
                type="button"
                class="icon-btn text-danger"
                [title]="'common.delete' | t"
                (click)="deleteNote(note)"
              >
                <span class="material-symbols-outlined">delete</span>
              </button>
            </div>
          </div>

          <div class="card-body">
            <p class="note-content">{{ note.contentMd }}</p>
          </div>

          <div class="card-footer">
            <span class="note-date">{{ note.modifiedAt | date:'short' }}</span>
          </div>
        </div>
      </div>

      <ng-template #emptyState>
        <div class="empty-state">
          <span class="material-symbols-outlined empty-icon">description</span>
          <h3>{{ 'notes.empty_title' | t }}</h3>
          <p>{{ 'notes.empty_desc' | t }}</p>
          <ui-button
            *ngIf="canCreate()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateModal()"
          >
            {{ 'notes.create' | t }}
          </ui-button>
        </div>
      </ng-template>

      <!-- Modal Create / Edit -->
      <ui-modal
        [isOpen]="isModalOpen()"
        [title]="editingNote() ? ('notes.edit_title' | t) : ('notes.create_title' | t)"
        (onClose)="closeModal()"
      >
        <div class="modal-form">
          <div class="form-group">
            <label class="form-label">{{ 'notes.title_label' | t }} *</label>
            <input
              type="text"
              class="form-control"
              [(ngModel)]="formData.title"
              [placeholder]="'notes.title_placeholder' | t"
            />
          </div>

          <div class="form-group">
            <label class="form-label">{{ 'notes.content_label' | t }}</label>
            <textarea
              class="form-control"
              rows="6"
              [(ngModel)]="formData.contentMd"
              [placeholder]="'notes.content_placeholder' | t"
            ></textarea>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label class="form-label">{{ 'notes.color_label' | t }}</label>
              <select class="form-control" [(ngModel)]="formData.color">
                <option value="default">{{ 'notes.color_default' | t }}</option>
                <option value="blue">{{ 'notes.color_blue' | t }}</option>
                <option value="green">{{ 'notes.color_green' | t }}</option>
                <option value="yellow">{{ 'notes.color_yellow' | t }}</option>
                <option value="purple">{{ 'notes.color_purple' | t }}</option>
                <option value="red">{{ 'notes.color_red' | t }}</option>
              </select>
            </div>

            <div class="form-group checkbox-group">
              <label class="checkbox-label">
                <input type="checkbox" [(ngModel)]="formData.isPinned" />
                <span>{{ 'notes.pinned_label' | t }}</span>
              </label>
            </div>
          </div>
        </div>

        <div class="modal-footer" slot="footer">
          <ui-button variant="secondary" (onClick)="closeModal()">{{ 'common.cancel' | t }}</ui-button>
          <ui-button variant="primary" (onClick)="saveNote()">{{ 'common.save' | t }}</ui-button>
        </div>
      </ui-modal>
    </div>
  `,
  styleUrl: './notes.component.css'
})
export class NotesComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private perm = inject(PermissionService);

  notes = signal<Note[]>([]);
  searchQuery = '';
  isModalOpen = signal(false);
  editingNote = signal<Note | null>(null);

  formData = {
    title: '',
    contentMd: '',
    color: 'default',
    isPinned: false
  };

  private readonly uiI18n = inject(I18nService);

  canCreate = computed(() => this.perm.hasPermission('notes', 'create'));
  canEdit = computed(() => this.perm.hasPermission('notes', 'update'));
  canDelete = computed(() => this.perm.hasPermission('notes', 'delete'));

  ngOnInit(): void {
    this.loadNotes();
  }

  loadNotes(): void {
    const params = this.searchQuery ? { q: this.searchQuery } : undefined;
    this.api.get<Note[]>('/notes', params).subscribe({
      next: (data) => this.notes.set(data || []),
      error: () => this.toast.error(this.uiI18n.translate('notes.load_error'))
    });
  }

  onSearchChange(): void {
    this.loadNotes();
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.loadNotes();
  }

  openCreateModal(): void {
    this.editingNote.set(null);
    this.formData = { title: '', contentMd: '', color: 'default', isPinned: false };
    this.isModalOpen.set(true);
  }

  openEditModal(note: Note): void {
    this.editingNote.set(note);
    this.formData = {
      title: note.title,
      contentMd: note.contentMd,
      color: note.color,
      isPinned: note.isPinned
    };
    this.isModalOpen.set(true);
  }

  closeModal(): void {
    this.isModalOpen.set(false);
  }

  saveNote(): void {
    if (!this.formData.title.trim()) {
      this.toast.error(this.uiI18n.translate('notes.title_required'));
      return;
    }

    const current = this.editingNote();
    if (current) {
      this.api.put<Note>(`/notes/${current.id}`, this.formData).subscribe({
        next: () => {
          this.toast.success(this.uiI18n.translate('notes.updated'));
          this.closeModal();
          this.loadNotes();
        },
        error: () => this.toast.error(this.uiI18n.translate('notes.save_error'))
      });
    } else {
      this.api.post<Note>('/notes', this.formData).subscribe({
        next: () => {
          this.toast.success(this.uiI18n.translate('notes.created'));
          this.closeModal();
          this.loadNotes();
        },
        error: () => this.toast.error(this.uiI18n.translate('notes.create_error'))
      });
    }
  }

  togglePin(note: Note): void {
    this.api.post<Note>(`/notes/${note.id}/pin`, {}).subscribe({
      next: () => this.loadNotes(),
      error: () => this.toast.error(this.uiI18n.translate('notes.pin_error'))
    });
  }

  deleteNote(note: Note): void {
    if (!confirm(this.uiI18n.translate('notes.delete_confirm', { title: note.title }))) return;
    this.api.delete(`/notes/${note.id}`).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('notes.deleted'));
        this.loadNotes();
      },
      error: () => this.toast.error(this.uiI18n.translate('notes.delete_error'))
    });
  }
}
