import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  OnDestroy,
  effect,
  signal,
  computed,
  inject,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { A11yModule } from '@angular/cdk/a11y';
import { Router } from '@angular/router';
import { CommandPaletteService } from '@core/services/command-palette.service';
import { SearchCategory, SearchHit, SearchResult } from '@core/models/search.models';
import { searchTarget } from '@core/services/search-target';
import { EMPTY, Subject, catchError, of, switchMap, timer } from 'rxjs';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';

import {
  RECENT_SEARCHES_STORAGE_KEY,
  MAX_RECENT_SEARCHES,
  CategoryItem,
  categoryItem,
} from './command-palette.models';
import { CommandPaletteResultsComponent } from './components/command-palette-results.component';
import { CommandPaletteFooterComponent } from './components/command-palette-footer.component';

export { RECENT_SEARCHES_STORAGE_KEY, MAX_RECENT_SEARCHES, type CategoryItem };

@Component({
  selector: 'app-command-palette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, A11yModule, CommandPaletteResultsComponent, CommandPaletteFooterComponent],
  templateUrl: './command-palette.component.html',
  styleUrl: './command-palette.component.css',
})
export class CommandPaletteComponent implements OnDestroy {
  readonly paletteService = inject(CommandPaletteService);
  private readonly router = inject(Router);
  private readonly uiI18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  readonly metadata = signal<SearchResult | null>(null);
  readonly retrySeconds = signal(0);
  readonly isLoading = signal<boolean>(false);
  readonly results = signal<SearchHit[]>([]);
  readonly errorMessage = signal<string>('');
  readonly recentSearches = signal<string[]>([]);
  readonly selectedIndex = signal(0);

  readonly searchQuery = signal('');

  /** The entities the person may search, as the server names them (ADR-0032, 10.3). */
  private readonly searchCategories = signal<SearchCategory[]>([]);

  /** "All" and a category per entity the person may search. */
  readonly categories = computed<CategoryItem[]>(() => [
    { value: 'ALL', label: 'search.entity.all', icon: 'apps' },
    ...this.searchCategories().map((category) => categoryItem(category)),
  ]);

  private static nextId = 0;
  entityType = 'ALL';
  private retryUntil = 0;
  private cooldownTimer?: ReturnType<typeof setInterval>;
  private readonly searchSubject = new Subject<string | null>();
  private readonly componentId = CommandPaletteComponent.nextId++;
  readonly titleId = `command-palette-title-${this.componentId}`;
  readonly inputId = `command-palette-input-${this.componentId}`;
  readonly listboxId = `command-palette-results-${this.componentId}`;
  private previouslyFocusedElement: HTMLElement | null = null;
  private wasOpen = false;

  constructor() {
    this.loadRecentSearches();

    effect(() => {
      const isOpen = this.paletteService.isOpen();
      if (isOpen && !this.wasOpen) {
        this.resetSearch();
        this.loadRecentSearches();
        this.loadCategories();
        this.previouslyFocusedElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        document.body.classList.add('palette-open');
        queueMicrotask(() => {
          if (!this.destroyRef.destroyed && this.paletteService.isOpen()) this.searchInput()?.nativeElement.focus();
        });
      } else if (!isOpen && this.wasOpen) {
        this.resetSearch();
        document.body.classList.remove('palette-open');
        const focusTarget = this.previouslyFocusedElement;
        queueMicrotask(() => {
          if (!this.destroyRef.destroyed && !this.paletteService.isOpen() && focusTarget?.isConnected)
            focusTarget.focus();
        });
        this.previouslyFocusedElement = null;
      }
      this.wasOpen = isOpen;
    });

    this.searchSubject
      .pipe(
        switchMap((query) => {
          if (query === null || !this.validQuery(query) || this.retryUntil > Date.now()) return EMPTY;
          // A new input cancels both the debounce timer and an older HTTP request.
          return timer(120).pipe(
            switchMap(() => this.paletteService.search(query, this.entityType)),
            catchError((error) => {
              this.results.set([]);
              this.isLoading.set(false);
              this.errorMessage.set(this.getSearchErrorMessage(error));
              if (error?.status === 429) this.startCooldown(error.retryAfterSeconds);
              return of(null);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (!res || !this.paletteService.isOpen()) return;
        // The server answers only the entities the person may search, each in the person's scope (ADR-0032, 10.3).
        this.results.set(res.hits || []);
        this.metadata.set(res);
        this.selectedIndex.set(0);
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
    if (
      (event.ctrlKey || event.metaKey) &&
      !event.altKey &&
      (event.code === 'KeyK' || event.key.toLowerCase() === 'k')
    ) {
      event.preventDefault();
      if (!event.repeat) this.paletteService.toggle();
    } else if (event.key === 'Escape' && this.paletteService.isOpen()) {
      event.preventDefault();
      event.stopPropagation();
      this.paletteService.close();
    } else if (
      this.paletteService.isOpen() &&
      event.target === this.searchInput()?.nativeElement &&
      this.results().length > 0
    ) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        this.selectedIndex.set((this.selectedIndex() + 1) % this.results().length);
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        this.selectedIndex.set((this.selectedIndex() - 1 + this.results().length) % this.results().length);
      } else if (event.key === 'Enter') {
        event.preventDefault();
        const hit = this.results()[this.selectedIndex()];
        if (hit) {
          this.navigateTo(hit);
        }
      }
    }
  }

  /** What the person typed; each keystroke restarts the debounced search. */
  onQueryInput(event: Event): void {
    this.searchQuery.set((event.target as HTMLInputElement).value);
    this.onSearchChange(this.searchQuery());
  }

  onSearchChange(query: string) {
    const normalized = query.trim();
    this.results.set([]);
    this.metadata.set(null);
    this.selectedIndex.set(0);
    this.errorMessage.set(
      this.retryUntil > Date.now()
        ? this.uiI18n.translate('search.rate_limited')
        : this.queryLength(normalized) > 200
          ? this.uiI18n.translate('search.query_too_long')
          : '',
    );
    this.isLoading.set(this.validQuery(normalized) && this.retryUntil <= Date.now());
    this.searchSubject.next(normalized);
  }

  setCategory(category: string) {
    this.entityType = category;
    this.onSearchChange(this.searchQuery());
  }

  clearQuery() {
    this.searchQuery.set('');
    this.onSearchChange('');
    this.searchInput()?.nativeElement.focus();
  }

  applySuggestion(suggestion: string) {
    this.searchQuery.set(suggestion);
    this.onSearchChange(suggestion);
    this.searchInput()?.nativeElement.focus();
  }

  retrySearch() {
    if (this.retryUntil > Date.now()) return;
    this.onSearchChange(this.searchQuery());
  }

  optionId(index: number): string {
    return `${this.listboxId}-option-${index}`;
  }

  navigateTo(hit: SearchHit) {
    const target = searchTarget(hit);
    if (!target) return;
    if (this.searchQuery().trim().length >= 2) {
      this.saveRecentSearch(this.searchQuery().trim());
    }
    this.paletteService.close();
    this.router.navigateByUrl(target);
  }

  selectRecent(query: string) {
    this.searchQuery.set(query);
    this.onSearchChange(query);
    this.searchInput()?.nativeElement.focus();
  }

  clearRecentSearches() {
    try {
      this.recentSearches.set([]);
      localStorage.removeItem(RECENT_SEARCHES_STORAGE_KEY);
    } catch {
      // localStorage may fail
    }
  }

  queryLength(query: string): number {
    return Array.from(query.trim()).length;
  }

  validQuery(query: string): boolean {
    const length = this.queryLength(query);
    return length >= 2 && length <= 200;
  }

  onBackdropClick(event: MouseEvent) {
    if ((event.target as HTMLElement).classList.contains('palette-backdrop')) {
      this.paletteService.close();
    }
  }

  /** Reads the categories again each time the palette opens: rights and modules may have changed meanwhile. */
  private loadCategories(): void {
    this.paletteService
      .categories()
      .pipe(
        catchError(() => of([] as SearchCategory[])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((categories) => {
        this.searchCategories.set(categories);
        if (this.entityType !== 'ALL' && !categories.some((category) => category.code === this.entityType)) {
          this.entityType = 'ALL';
        }
      });
  }

  private resetSearch(): void {
    this.searchQuery.set('');
    this.onSearchChange('');
  }

  private loadRecentSearches() {
    try {
      const stored = localStorage.getItem(RECENT_SEARCHES_STORAGE_KEY);
      if (stored) {
        const parsed = JSON.parse(stored);
        if (Array.isArray(parsed)) {
          this.recentSearches.set(parsed.filter((item) => typeof item === 'string'));
        }
      }
    } catch {
      // localStorage may fail or be restricted
    }
  }

  private saveRecentSearch(query: string) {
    try {
      const current = this.recentSearches().filter((q) => q.toLowerCase() !== query.toLowerCase());
      const updated = [query, ...current].slice(0, MAX_RECENT_SEARCHES);
      this.recentSearches.set(updated);
      localStorage.setItem(RECENT_SEARCHES_STORAGE_KEY, JSON.stringify(updated));
    } catch {
      // localStorage may fail
    }
  }

  private startCooldown(seconds: unknown): void {
    const bounded =
      typeof seconds === 'number' && Number.isSafeInteger(seconds) && seconds >= 0 ? Math.min(seconds, 300) : 1;
    this.retryUntil = Date.now() + bounded * 1000;
    this.retrySeconds.set(bounded);
    clearInterval(this.cooldownTimer);
    this.cooldownTimer = setInterval(() => {
      this.retrySeconds.set(Math.max(0, Math.ceil((this.retryUntil - Date.now()) / 1000)));
      if (this.retrySeconds() === 0) clearInterval(this.cooldownTimer);
    }, 250);
  }

  private getSearchErrorMessage(error: unknown): string {
    if ((error as { status?: number })?.status === 429) return this.uiI18n.translate('search.rate_limited');
    if (error && typeof error === 'object') {
      const detail = (error as { detail?: unknown }).detail;
      if (typeof detail === 'string' && detail.trim()) return detail;
    }
    return this.uiI18n.translate('layout.command_palette.search_failed');
  }
}
