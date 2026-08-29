import { Component, HostListener, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CommandPaletteService } from '../../core/services/command-palette.service';
import { SearchHit } from '../../core/models/search.models';
import { Subject, debounceTime, distinctUntilChanged, switchMap, of } from 'rxjs';

@Component({
  selector: 'app-command-palette',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div *ngIf="paletteService.isOpen()" class="palette-backdrop" (click)="onBackdropClick($event)">
      <div class="palette-dialog" role="dialog">
        <div class="palette-search-box">
          <span class="material-symbols-outlined search-icon">search</span>
          <input
            type="text"
            class="palette-input"
            placeholder="Поиск задач, проектов, пользователей... (Esc для закрытия)"
            [(ngModel)]="searchQuery"
            (ngModelChange)="onSearchChange($event)"
            autofocus
          />
          <kbd class="esc-badge">ESC</kbd>
        </div>

        <div class="palette-results">
          <div *ngIf="isLoading()" class="palette-loading">
            Поиск...
          </div>

          <div *ngIf="!isLoading() && results().length === 0 && searchQuery.length >= 2" class="palette-empty">
            Ничего не найдено по запросу «{{ searchQuery }}»
          </div>

          <div *ngIf="!isLoading() && searchQuery.length < 2" class="palette-hint">
            Введите минимум 2 символа для мгновенного поиска...
          </div>

          <div class="results-list" *ngIf="results().length > 0">
            <div
              *ngFor="let hit of results(); let idx = index"
              class="result-item"
              [class.active]="selectedIndex === idx"
              (click)="navigateTo(hit)"
              (mouseenter)="selectedIndex = idx"
            >
              <div class="result-icon-box" [class]="'icon-' + hit.entityType.toLowerCase()">
                <span class="material-symbols-outlined">
                  {{ getIcon(hit.entityType) }}
                </span>
              </div>
              <div class="result-info">
                <div class="result-title">{{ hit.title }}</div>
                <div class="result-desc">{{ hit.description }}</div>
              </div>
              <div class="result-badge" [class]="'badge-' + hit.entityType.toLowerCase()">
                {{ getEntityBadge(hit.entityType) }}
              </div>
            </div>
          </div>

        </div>
      </div>
    </div>
  `,
  styles: [`
    .palette-backdrop {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      background-color: rgba(15, 23, 42, 0.65);
      backdrop-filter: blur(4px);
      display: flex;
      justify-content: center;
      padding-top: 15vh;
      z-index: 2500;
    }

    .palette-dialog {
      background-color: var(--bg-surface);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-overlay);
      border: 1px solid var(--border-color);
      width: 100%;
      max-width: 620px;
      max-height: 480px;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      animation: paletteIn 0.15s ease-out;
    }

    .palette-search-box {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 16px;
      border-bottom: 1px solid var(--border-color);
    }

    .search-icon {
      color: var(--text-muted);
      font-size: 22px;
    }

    .palette-input {
      flex: 1;
      border: none;
      background: transparent;
      font-size: 15px;
      font-family: inherit;
      color: var(--text-main);
      outline: none;
    }

    .esc-badge {
      font-size: 10px;
      font-family: inherit;
      padding: 2px 6px;
      border-radius: var(--radius-sm);
      background-color: var(--bg-hover);
      color: var(--text-muted);
      border: 1px solid var(--border-color);
    }

    .palette-results {
      padding: 8px;
      overflow-y: auto;
      flex: 1;
    }

    .palette-loading, .palette-empty, .palette-hint {
      padding: 24px;
      text-align: center;
      color: var(--text-muted);
      font-size: 13px;
    }

    .results-list {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .result-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 12px;
      border-radius: var(--radius-md);
      cursor: pointer;
      transition: background-color 0.1s ease;
    }

    .result-item:hover {
      background-color: var(--bg-hover);
    }

    .result-icon-box {
      width: 32px;
      height: 32px;
      border-radius: var(--radius-sm);
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .icon-user { background-color: var(--info-bg); color: var(--info); }
    .icon-task { background-color: var(--success-bg); color: var(--success); }
    .icon-project { background-color: var(--warning-bg); color: var(--warning); }

    .result-info {
      flex: 1;
      min-width: 0;
    }

    .result-title {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .result-desc {
      font-size: 11px;
      color: var(--text-muted);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .result-badge {
      font-size: 10px;
      padding: 2px 6px;
      border-radius: var(--radius-sm);
      background-color: var(--bg-hover);
      color: var(--text-muted);
    }

    @keyframes paletteIn {
      from {
        opacity: 0;
        transform: translateY(-8px) scale(0.98);
      }
      to {
        opacity: 1;
        transform: translateY(0) scale(1);
      }
    }
  `]
})
export class CommandPaletteComponent {
  searchQuery = '';
  selectedIndex = 0;
  readonly isLoading = signal<boolean>(false);
  readonly results = signal<SearchHit[]>([]);
  private searchSubject = new Subject<string>();

  constructor(
    public paletteService: CommandPaletteService,
    private router: Router
  ) {
    this.searchSubject.pipe(
      debounceTime(120),
      distinctUntilChanged(),
      switchMap(query => {
        if (!query || query.trim().length < 2) {
          this.isLoading.set(false);
          return of({ query, totalHits: 0, hits: [] });
        }
        this.isLoading.set(true);
        return this.paletteService.search(query.trim());
      })
    ).subscribe(res => {
      this.results.set(res.hits || []);
      this.selectedIndex = 0;
      this.isLoading.set(false);
    });
  }

  @HostListener('document:keydown', ['$event'])
  handleKeyboard(event: KeyboardEvent) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.paletteService.toggle();
      this.searchQuery = '';
      this.results.set([]);
      this.selectedIndex = 0;
    } else if (event.key === 'Escape' && this.paletteService.isOpen()) {
      this.paletteService.close();
    } else if (this.paletteService.isOpen() && this.results().length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        this.selectedIndex = (this.selectedIndex + 1) % this.results().length;
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        this.selectedIndex = (this.selectedIndex - 1 + this.results().length) % this.results().length;
      } else if (event.key === 'Enter') {
        event.preventDefault();
        const hit = this.results()[this.selectedIndex];
        if (hit) {
          this.navigateTo(hit);
        }
      }
    }
  }

  onSearchChange(query: string) {
    this.searchSubject.next(query);
  }

  navigateTo(hit: SearchHit) {
    this.paletteService.close();
    if (hit.entityType === 'TASK') {
      this.router.navigate(['/tasks']);
    } else if (hit.entityType === 'PROJECT') {
      this.router.navigate(['/tasks/projects']);
    } else if (hit.entityType === 'USER') {
      this.router.navigate(['/iam/users']);
    }
  }

  onBackdropClick(event: MouseEvent) {
    if ((event.target as HTMLElement).classList.contains('palette-backdrop')) {
      this.paletteService.close();
    }
  }

  getIcon(type: string): string {
    switch (type) {
      case 'USER': return 'person';
      case 'TASK': return 'task_alt';
      case 'PROJECT': return 'folder';
      default: return 'description';
    }
  }

  getEntityBadge(type: string): string {
    switch (type) {
      case 'TASK': return 'Задача';
      case 'PROJECT': return 'Проект';
      case 'USER': return 'Сотрудник';
      default: return type;
    }
  }
}

