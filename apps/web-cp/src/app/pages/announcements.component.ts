import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Announcement, AnnouncementContent, CpApiService } from '../core/cp-api.service';
import { dt, errorText } from '../core/format';

@Component({
  selector: 'cp-announcements',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <!-- Page Header -->
    <div class="view-header">
      <div class="header-left">
        <div>
          <div style="display: flex; align-items: center; gap: 10px;">
            <h1 class="view-title">Системные объявления</h1>
            <span class="count-badge">{{ items().length }}</span>
          </div>
          <p class="view-desc">Таргетированные информационные и предупреждающие баннеры в клиентских экземплярах</p>
        </div>
      </div>

      <div class="header-right" *ngIf="canEdit()">
        <button type="button" class="btn btn-primary" (click)="openCreateModal()">
          <span class="material-symbols-outlined" aria-hidden="true">add</span>
          <span>Новое объявление</span>
        </button>
      </div>
    </div>

    <!-- Error Alert -->
    <div *ngIf="error()" class="alert alert-error" role="alert">
      <span class="material-symbols-outlined">error</span>
      <span>{{ error() }}</span>
    </div>

    <!-- Filter Toolbar -->
    <div class="toolbar">
      <div class="search-field">
        <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
        <label class="sr-only" for="ann-search">Поиск объявлений</label>
        <input
          id="ann-search"
          name="annSearch"
          type="text"
          class="search-input"
          placeholder="Поиск по заголовку или тексту..."
          [(ngModel)]="searchQuery"
        />
      </div>

      <div class="status-tabs" role="group" aria-label="Фильтр объявлений">
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedState === 'all'"
          (click)="selectedState = 'all'"
        >
          Все ({{ items().length }})
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedState === 'published'"
          (click)="selectedState = 'published'"
        >
          <span class="status-tab-dot" style="background-color: var(--success);"></span>
          Опубликованные
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedState === 'draft'"
          (click)="selectedState = 'draft'"
        >
          <span class="status-tab-dot" style="background-color: var(--warning);"></span>
          Черновики
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedState === 'archived'"
          (click)="selectedState = 'archived'"
        >
          <span class="status-tab-dot" style="background-color: var(--text-light);"></span>
          Архив
        </button>
      </div>
    </div>

    <!-- Data Table Card -->
    <div class="card">
      <div class="table-scroll" role="region" aria-label="Таблица объявлений" tabindex="0">
        <table aria-label="Все объявления">
          <thead>
            <tr>
              <th>Заголовок и содержание</th>
              <th>Тип</th>
              <th>Состояние</th>
              <th>Адресаты</th>
              <th>Опубликовано</th>
              <th style="text-align: right;">Действия</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let a of filteredItems(); trackBy: trackById">
              <td style="max-width: 380px;">
                <div style="font-weight: 600;">{{ ru(a)?.title || '(без заголовка)' }}</div>
                <div style="font-size: 12px; color: var(--text-muted); margin-top: 3px; line-height: 1.4;">
                  {{ ru(a)?.body }}
                </div>
              </td>
              <td>
                <span class="badge" [ngClass]="typeClass(a.bannerType)">
                  {{ typeText(a.bannerType) }}
                </span>
              </td>
              <td>
                <span class="badge" [ngClass]="stateClass(a.state)">
                  {{ stateText(a.state) }}
                </span>
              </td>
              <td style="font-size: 12px; color: var(--text-muted);">{{ a.targets }}</td>
              <td style="font-size: 12px; color: var(--text-muted);">{{ dt(a.publishedAt) }}</td>
              <td style="text-align: right; white-space: nowrap;">
                <div *ngIf="canEdit()" style="display: inline-flex; gap: 6px;">
                  <button
                    *ngIf="a.state === 'draft'"
                    type="button"
                    class="btn btn-sm btn-primary"
                    (click)="publish(a.id)"
                    [disabled]="busy()"
                  >
                    <span class="material-symbols-outlined" style="font-size: 14px;">campaign</span>
                    <span>Опубликовать</span>
                  </button>
                  <button
                    *ngIf="a.state === 'published'"
                    type="button"
                    class="btn btn-sm btn-secondary"
                    (click)="archive(a.id)"
                    [disabled]="busy()"
                  >
                    <span class="material-symbols-outlined" style="font-size: 14px;">archive</span>
                    <span>В архив</span>
                  </button>
                </div>
              </td>
            </tr>
            <tr *ngIf="filteredItems().length === 0">
              <td colspan="6" class="empty">
                <div style="display: flex; flex-direction: column; align-items: center; gap: 8px;">
                  <span class="material-symbols-outlined" style="font-size: 36px; color: var(--text-light);">campaign</span>
                  <span>Объявлений по заданным фильтрам не найдено.</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- MODAL: Создание объявления -->
    <div class="modal-overlay" *ngIf="showModal">
      <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="create-ann-title">
        <div class="modal-header">
          <h2 id="create-ann-title" class="modal-title">Создание системного объявления</h2>
          <button type="button" class="btn-icon" (click)="showModal = false" aria-label="Закрыть окно">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>
        <form (ngSubmit)="create()">
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label" for="ann-type">Тип баннера *</label>
              <select id="ann-type" name="annType" class="form-select" [(ngModel)]="bannerType">
                <option value="info">Информационное (Синий баннер)</option>
                <option value="warning">Предупреждение (Оранжевый баннер)</option>
                <option value="critical">Критическое (Красный баннер)</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label" for="ann-title">Заголовок *</label>
              <input
                id="ann-title"
                name="annTitle"
                type="text"
                class="form-input"
                [(ngModel)]="title"
                placeholder="Плановое техническое обслуживание"
                required
              />
            </div>
            <div class="form-group">
              <label class="form-label" for="ann-body">Текст сообщения *</label>
              <textarea
                id="ann-body"
                name="annBody"
                class="form-textarea"
                rows="4"
                [(ngModel)]="body"
                placeholder="В субботу с 02:00 до 04:00 МСК будут проводиться работы по обновлению платформы..."
                required
              ></textarea>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-secondary" (click)="showModal = false">Отмена</button>
            <button type="submit" class="btn btn-primary" [disabled]="busy() || !title || !body">
              {{ busy() ? 'Сохраняем...' : 'Сохранить черновик' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  `
})
export class AnnouncementsComponent implements OnInit {
  private api = inject(CpApiService);

  items = signal<Announcement[]>([]);
  busy = signal(false);
  error = signal('');
  searchQuery = '';
  selectedState: 'all' | 'published' | 'draft' | 'archived' = 'all';

  showModal = false;
  bannerType = 'info';
  title = '';
  body = '';

  dt = dt;

  canEdit = computed(() => {
    const roles = this.api.user()?.roles ?? [];
    return roles.includes('cp-admin') || roles.includes('cp-editor');
  });

  filteredItems(): Announcement[] {
    const q = this.searchQuery.trim().toLowerCase();
    return this.items().filter(a => {
      const content = this.ru(a);
      const matchesText = !q
        || (content?.title && content.title.toLowerCase().includes(q))
        || (content?.body && content.body.toLowerCase().includes(q));

      if (!matchesText) return false;
      if (this.selectedState !== 'all' && a.state !== this.selectedState) return false;

      return true;
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  trackById(index: number, item: Announcement): number {
    return item.id;
  }

  ru(a: Announcement): AnnouncementContent | undefined {
    return a.contents.find(c => c.language === 'ru') ?? a.contents[0];
  }

  openCreateModal(): void {
    this.bannerType = 'info';
    this.title = '';
    this.body = '';
    this.showModal = true;
  }

  async load(): Promise<void> {
    this.busy.set(true);
    try {
      this.items.set(await this.api.announcements());
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось загрузить объявления'));
    } finally {
      this.busy.set(false);
    }
  }

  async create(): Promise<void> {
    if (!this.title || !this.body) return;
    this.busy.set(true);
    try {
      await this.api.createAnnouncement(this.bannerType, this.title.trim(), this.body.trim());
      this.showModal = false;
      this.title = '';
      this.body = '';
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось создать объявление'));
    } finally {
      this.busy.set(false);
    }
  }

  async publish(id: number): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.publishAnnouncement(id);
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось опубликовать объявление'));
    } finally {
      this.busy.set(false);
    }
  }

  async archive(id: number): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.archiveAnnouncement(id);
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось архивировать объявление'));
    } finally {
      this.busy.set(false);
    }
  }

  typeClass(type: string): string {
    switch (type) {
      case 'info': return 'badge-info';
      case 'warning': return 'badge-warning';
      case 'critical': return 'badge-danger';
      default: return 'badge-neutral';
    }
  }

  typeText(type: string): string {
    switch (type) {
      case 'info': return 'Информация';
      case 'warning': return 'Предупреждение';
      case 'critical': return 'Критично';
      default: return type;
    }
  }

  stateClass(state: string): string {
    switch (state) {
      case 'published': return 'badge-success';
      case 'draft': return 'badge-neutral';
      case 'archived': return 'badge-warning';
      default: return 'badge-neutral';
    }
  }

  stateText(state: string): string {
    switch (state) {
      case 'published': return 'Опубликовано';
      case 'draft': return 'Черновик';
      case 'archived': return 'В архиве';
      default: return state;
    }
  }
}
