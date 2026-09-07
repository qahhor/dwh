import { Component, DestroyRef, ElementRef, HostListener, OnDestroy, ViewChild, effect, signal, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { A11yModule } from '@angular/cdk/a11y';
import { Router } from '@angular/router';
import { CommandPaletteService } from '../../core/services/command-palette.service';
import { SearchHit, SearchResult } from '../../core/models/search.models';
import { searchTarget } from '../../core/services/search-target';
import { EMPTY, Subject, catchError, of, switchMap, timer } from 'rxjs';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';

@Component({
  selector: 'app-command-palette',
  standalone: true,
  imports: [
    TranslatePipe,CommonModule, FormsModule, A11yModule],
  template: `
    <div *ngIf="paletteService.isOpen()" class="palette-backdrop" (click)="onBackdropClick($event)">
      <div
        class="palette-dialog"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="titleId"
        cdkTrapFocus
        [cdkTrapFocusAutoCapture]="true"
      >
        <h2 class="sr-only" [id]="titleId">{{ 'layout.command_palette.globalnyy_poisk' | t }}</h2>
        <div class="palette-search-box">
          <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
          <label class="sr-only" [for]="inputId">{{ 'layout.command_palette.poisk_zadach_proektov_i_polzovateley' | t }}</label>
          <input
            #searchInput
            [id]="inputId"
            type="text"
            class="palette-input"
            role="combobox"
            aria-autocomplete="list"
            [attr.aria-expanded]="results().length > 0"
            [attr.aria-controls]="results().length > 0 ? listboxId : null"
            [attr.aria-activedescendant]="results().length > 0 ? optionId(selectedIndex) : null"
            [placeholder]="'layout.command_palette.poisk_zadach_proektov_polzovateley_esc_dlya_zakr' | t"
            [(ngModel)]="searchQuery"
            (ngModelChange)="onSearchChange($event)"
          />
          <kbd class="esc-badge" aria-hidden="true">ESC</kbd>
          <button type="button" class="palette-close" [attr.aria-label]="'layout.command_palette.close_search' | t" (click)="paletteService.close()">
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>

        <div class="palette-category">
          <label for="search-category">{{ 'search.category' | t }}</label>
          <select id="search-category" [(ngModel)]="entityType" (ngModelChange)="onSearchChange(searchQuery)">
            <option value="ALL">{{ 'search.all' | t }}</option>
            <option value="TASK">{{ 'nav.tasks' | t }}</option>
            <option value="PROJECT">{{ 'nav.projects' | t }}</option>
            <option value="USER">{{ 'nav.users' | t }}</option>
          </select>
        </div>
        <div class="palette-results">
          <div *ngIf="isLoading()" class="palette-loading" role="status" aria-live="polite">
            {{ 'layout.app_shell.poisk' | t }}
          </div>

          <div *ngIf="!isLoading() && errorMessage()" class="palette-error" role="alert">
            <span>{{ errorMessage() }}</span>
            <span *ngIf="retrySeconds() > 0">{{ 'search.retry_countdown' | t:{seconds: retrySeconds()} }}</span>
            <button type="button" class="palette-retry" [disabled]="retrySeconds() > 0 || !validQuery(searchQuery)" (click)="retrySearch()">{{ 'announcements.povtorit' | t }}</button>
          </div>

          <div *ngIf="metadata()?.degraded" class="palette-degraded" role="status">{{ 'search.degraded' | t }}</div>
          <div *ngIf="metadata() as meta" class="palette-count" role="status">
            {{ (meta.foundHits === null ? 'search.returned' : 'search.returned_found') | t:{returned: results().length, found: meta.foundHits ?? 0} }}
            <span *ngIf="meta.hasMore">{{ 'search.has_more' | t }}</span>
          </div>
          <div *ngIf="!isLoading() && !errorMessage() && metadata() && results().length === 0" class="palette-empty" role="status">
            {{ 'layout.command_palette.nothing_found_for' | t:{query: searchQuery} }}
          </div>

          <div *ngIf="!isLoading() && queryLength(searchQuery) < 2" class="palette-hint">
            {{ 'layout.command_palette.vvedite_minimum_2_simvola_dlya_mgnovennogo_poisk' | t }}
          </div>

          <div class="results-list" *ngIf="results().length > 0" role="listbox" [id]="listboxId" [attr.aria-label]="'layout.command_palette.rezultaty_poiska' | t">
            <button
              *ngFor="let hit of results(); let idx = index"
              type="button"
              role="option"
              [id]="optionId(idx)"
              class="result-item"
              [class.active]="selectedIndex === idx"
              [attr.aria-selected]="selectedIndex === idx"
              (click)="navigateTo(hit)"
              (mouseenter)="selectedIndex = idx"
            >
              <div class="result-icon-box" [class]="'icon-' + hit.entityType.toLowerCase()">
                <span class="material-symbols-outlined" aria-hidden="true">
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
            </button>
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
      position: static;
      flex-shrink: 0;
      color: var(--text-muted);
      font-size: 22px;
    }

    .palette-input {
      flex: 1;
      min-width: 0;
      border: none;
      background: transparent;
      font-size: 15px;
      font-family: inherit;
      color: var(--text-main);
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

    .palette-loading, .palette-empty, .palette-hint, .palette-error {
      padding: 24px;
      text-align: center;
      color: var(--text-muted);
      font-size: 13px;
    }
    .palette-category { display: flex; align-items: center; gap: 12px; padding: 8px 16px; }
    .palette-category select { min-width: 0; max-width: 100%; color: var(--text-main); background: var(--bg-surface); }
    .palette-count, .palette-degraded { padding: 8px 12px; font-size: 12px; color: var(--text-muted); }
    .palette-degraded { color: var(--warning); }
    .palette-retry:disabled { opacity: .6; cursor: default; }

    .palette-close {
      display: grid;
      place-items: center;
      width: 34px;
      height: 34px;
      flex-shrink: 0;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background: var(--bg-surface);
      color: var(--text-muted);
      cursor: pointer;
    }
    .palette-close:hover { background: var(--bg-hover); color: var(--text-main); }
    .palette-close:focus-visible, .palette-retry:focus-visible, .result-item:focus-visible {
      outline: 2px solid var(--focus-ring, var(--primary));
      outline-offset: -2px;
    }

    .palette-error {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 10px;
      color: var(--danger);
    }

    .palette-retry {
      min-height: 30px;
      padding: 4px 12px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background: var(--bg-surface);
      color: var(--text-main);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
    }

    .palette-retry:hover { border-color: var(--primary); color: var(--primary); }

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
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      text-align: left;
    }

    .result-item:hover,
    .result-item.active {
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

    @media (max-width: 680px) {
      .palette-backdrop { padding: 10vh 12px 12px; }
      .palette-dialog { max-height: 75vh; }
      .result-badge { display: none; }
      .esc-badge { display: none; }
      .palette-search-box { padding: 8px; gap: 8px; }
      .palette-input { font-size: 16px; }
      .palette-close { width: 44px; height: 44px; }
    }
  `]
})
export class CommandPaletteComponent implements OnDestroy {
  private readonly uiI18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);
  private static nextId = 0;

  searchQuery = '';
  entityType = 'ALL';
  readonly metadata = signal<SearchResult | null>(null);
  readonly retrySeconds = signal(0);
  private retryUntil = 0;
  private cooldownTimer?: ReturnType<typeof setInterval>;
  selectedIndex = 0;
  readonly isLoading = signal<boolean>(false);
  readonly results = signal<SearchHit[]>([]);
  readonly errorMessage = signal<string>('');
  private readonly searchSubject = new Subject<string | null>();
  private readonly componentId = CommandPaletteComponent.nextId++;
  readonly titleId = `command-palette-title-${this.componentId}`;
  readonly inputId = `command-palette-input-${this.componentId}`;
  readonly listboxId = `command-palette-results-${this.componentId}`;
  private previouslyFocusedElement: HTMLElement | null = null;
  private wasOpen = false;

  @ViewChild('searchInput') private searchInput?: ElementRef<HTMLInputElement>;

  constructor(
    public paletteService: CommandPaletteService,
    private router: Router
  ) {
    effect(() => {
      const isOpen = this.paletteService.isOpen();
      if (isOpen && !this.wasOpen) {
        this.resetSearch();
        this.previouslyFocusedElement = document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null;
        document.body.classList.add('palette-open');
        queueMicrotask(() => {
          if (!this.destroyRef.destroyed && this.paletteService.isOpen()) this.searchInput?.nativeElement.focus();
        });
      } else if (!isOpen && this.wasOpen) {
        this.resetSearch();
        document.body.classList.remove('palette-open');
        const focusTarget = this.previouslyFocusedElement;
        queueMicrotask(() => {
          if (!this.destroyRef.destroyed && !this.paletteService.isOpen() && focusTarget?.isConnected) focusTarget.focus();
        });
        this.previouslyFocusedElement = null;
      }
      this.wasOpen = isOpen;
    });

    this.searchSubject.pipe(
      switchMap(query => {
        if (query === null || !this.validQuery(query) || this.retryUntil > Date.now()) return EMPTY;
        // A new input cancels both the debounce timer and an older HTTP request.
        return timer(120).pipe(
          switchMap(() => this.paletteService.search(query, this.entityType)),
          catchError(error => {
            this.results.set([]);
            this.isLoading.set(false);
            this.errorMessage.set(this.getSearchErrorMessage(error));
            if (error?.status === 429) this.startCooldown(error.retryAfterSeconds);
            return of(null);
          })
        );
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(res => {
      if (!res || !this.paletteService.isOpen()) return;
      this.results.set(res.hits || []);
      this.metadata.set(res);
      this.selectedIndex = 0;
      this.isLoading.set(false);
    });
  }

  ngOnDestroy() {
    clearInterval(this.cooldownTimer);
    this.paletteService.close();
    this.searchSubject.complete();
    document.body.classList.remove('palette-open');
  }

  @HostListener('document:keydown', ['$event'])
  handleKeyboard(event: KeyboardEvent) {
    if (event.defaultPrevented || event.isComposing) return;
    if ((event.ctrlKey || event.metaKey) && !event.altKey && (event.code === 'KeyK' || event.key.toLowerCase() === 'k')) {
      event.preventDefault();
      if (!event.repeat) this.paletteService.toggle();
    } else if (event.key === 'Escape' && this.paletteService.isOpen()) {
      event.preventDefault();
      event.stopPropagation();
      this.paletteService.close();
    } else if (this.paletteService.isOpen() && event.target === this.searchInput?.nativeElement && this.results().length > 0) {
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
    const normalized = query.trim();
    this.results.set([]);
    this.metadata.set(null);
    this.selectedIndex = 0;
    this.errorMessage.set(this.retryUntil > Date.now() ? this.uiI18n.translate('search.rate_limited') :
      this.queryLength(normalized) > 200 ? this.uiI18n.translate('search.query_too_long') : '');
    this.isLoading.set(this.validQuery(normalized) && this.retryUntil <= Date.now());
    this.searchSubject.next(normalized);
  }

  retrySearch() {
    if (this.retryUntil > Date.now()) return;
    this.onSearchChange(this.searchQuery);
  }

  private resetSearch(): void {
    this.searchQuery = '';
    this.onSearchChange('');
  }

  optionId(index: number): string {
    return `${this.listboxId}-option-${index}`;
  }

  navigateTo(hit: SearchHit) {
    const target = searchTarget(hit);
    if (!target) return;
    this.paletteService.close();
    this.router.navigate(target);
  }

  queryLength(query: string): number { return Array.from(query.trim()).length; }

  validQuery(query: string): boolean {
    const length = this.queryLength(query);
    return length >= 2 && length <= 200;
  }

  private startCooldown(seconds: unknown): void {
    const bounded = typeof seconds === 'number' && Number.isSafeInteger(seconds) && seconds >= 0
      ? Math.min(seconds, 300) : 1;
    this.retryUntil = Date.now() + bounded * 1000;
    this.retrySeconds.set(bounded);
    clearInterval(this.cooldownTimer);
    this.cooldownTimer = setInterval(() => {
      this.retrySeconds.set(Math.max(0, Math.ceil((this.retryUntil - Date.now()) / 1000)));
      if (this.retrySeconds() === 0) clearInterval(this.cooldownTimer);
    }, 250);
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
      case 'TASK': return this.uiI18n.translate('tasks.zadacha');
      case 'PROJECT': return this.uiI18n.translate('projects.proekt');
      case 'USER': return this.uiI18n.translate('analytics.sotrudnik');
      default: return type;
    }
  }

  private getSearchErrorMessage(error: unknown): string {
    if ((error as { status?: number })?.status === 429) return this.uiI18n.translate('search.rate_limited');
    if (error && typeof error === 'object') {
      const detail = (error as { detail?: unknown }).detail;
      if (typeof detail === 'string' && detail.trim()) return detail;
    }
    return this.uiI18n.translate('layout.command_palette.ne_udalos_vypolnit_poisk_proverte_soedinenie_i_p');
  }
}
