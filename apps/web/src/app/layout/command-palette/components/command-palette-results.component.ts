import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { SearchHit, SearchResult } from '@core/models/search.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { CategoryItem, DEFAULT_CATEGORY_ICON } from '../command-palette.models';

@Component({
  selector: 'app-command-palette-results',
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="palette-results">
      @if (isLoading()) {
        <div class="palette-loading" role="status" aria-live="polite">
          {{ 'layout.app_shell.search' | t }}
        </div>
      }

      @if (!isLoading() && errorMessage()) {
        <div class="palette-error" role="alert">
          <span>{{ errorMessage() }}</span>
          @if (retrySeconds() > 0) {
            <span>{{ 'search.retry_countdown' | t: { seconds: retrySeconds() } }}</span>
          }
          <button
            type="button"
            class="palette-retry"
            [disabled]="retrySeconds() > 0 || !validQuery()"
            (click)="retry.emit()"
          >
            {{ 'announcements.list.retry' | t }}
          </button>
        </div>
      }

      @if (metadata()?.degraded) {
        <div class="palette-degraded" role="status">{{ 'search.degraded' | t }}</div>
      }

      @if (metadata(); as meta) {
        <div class="palette-count" role="status">
          {{
            (meta.foundHits === null ? 'search.returned' : 'search.returned_found')
              | t: { returned: results().length, found: meta.foundHits ?? 0 }
          }}
          @if (meta.hasMore) {
            <span>{{ 'search.has_more' | t }}</span>
          }
        </div>
      }

      @if (!isLoading() && !errorMessage() && metadata() && results().length === 0) {
        <div class="palette-empty" role="status">
          {{ 'layout.command_palette.nothing_found_for' | t: { query: searchQuery() } }}
        </div>
      }

      <!-- Hint & Recent Searches -->
      @if (!isLoading() && queryLength(searchQuery()) < 2) {
        <div class="palette-hint">
          <div class="hint-text">
            <span class="material-symbols-outlined hint-icon" aria-hidden="true">info</span>
            <span>{{ 'layout.command_palette.min_query_hint' | t }}</span>
          </div>

          @if (recentSearches().length > 0) {
            <div class="recent-searches">
              <div class="recent-header">
                <span class="recent-title">{{ 'search.recent_searches' | t }}</span>
                <button type="button" class="recent-clear-btn" (click)="clearRecent.emit()">
                  {{ 'search.clear_recent' | t }}
                </button>
              </div>
              <div class="recent-chips">
                @for (item of recentSearches(); track item) {
                  <button type="button" class="recent-chip" (click)="selectRecent.emit(item)">
                    <span class="material-symbols-outlined chip-icon" aria-hidden="true">history</span>
                    <span>{{ item }}</span>
                  </button>
                }
              </div>
            </div>
          }
        </div>
      }

      <!-- Results List -->
      @if (results().length > 0) {
        <div class="results-list" role="listbox" [id]="listboxId()" [attr.aria-label]="'search.results' | t">
          @for (hit of results(); track hit; let i = $index) {
            <button
              type="button"
              class="result-item"
              role="option"
              [id]="optionId(i)"
              [attr.aria-selected]="i === selectedIndex()"
              [class.active]="i === selectedIndex()"
              (click)="selectHit.emit(hit)"
            >
              <div class="result-icon-box">
                <span class="material-symbols-outlined" aria-hidden="true">{{ getIcon(hit.entityType) }}</span>
              </div>
              <div class="result-info">
                <div class="result-title">{{ hit.title }}</div>
                @if (hit.description) {
                  <div class="result-desc">{{ hit.description }}</div>
                }
              </div>
              <span class="result-badge">{{ getEntityBadge(hit.entityType) }}</span>
            </button>
          }
        </div>
      }
    </div>
  `,
  styleUrl: './command-palette-results.component.css',
})
export class CommandPaletteResultsComponent {
  private readonly uiI18n = inject(I18nService);

  readonly isLoading = input.required<boolean>();
  readonly errorMessage = input.required<string>();
  readonly retrySeconds = input.required<number>();
  readonly searchQuery = input.required<string>();
  readonly results = input.required<SearchHit[]>();
  readonly recentSearches = input.required<string[]>();
  readonly selectedIndex = input.required<number>();
  readonly listboxId = input.required<string>();
  readonly validQuery = input.required<boolean>();

  readonly metadata = input<SearchResult | null>(null);
  /** The categories of the palette: the icon and name of each entity a hit may belong to. */
  readonly categories = input<CategoryItem[]>([]);

  readonly retry = output<void>();
  readonly selectRecent = output<string>();
  readonly clearRecent = output<void>();
  readonly selectHit = output<SearchHit>();

  optionId(index: number): string {
    return `${this.listboxId()}-option-${index}`;
  }

  queryLength(query: string): number {
    return Array.from(query.trim()).length;
  }

  /** The icon of a hit: its entity's, as the server names the categories (ADR-0032, 10.3). */
  getIcon(entity: string): string {
    return this.category(entity)?.icon ?? DEFAULT_CATEGORY_ICON;
  }

  /** The name of a hit's entity, or its code when the person has no category of it. */
  getEntityBadge(entity: string): string {
    const category = this.category(entity);
    return category ? this.uiI18n.translate(category.label) : entity;
  }

  private category(entity: string): CategoryItem | undefined {
    return this.categories().find((category) => category.value === entity);
  }
}
