import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { NavigationService } from '@core/services/navigation.service';
import { PermissionService } from '@core/services/permission.service';
import {
  ReportCatalogApi,
  type ReportCatalogFilter,
  type ReportViewItem,
} from '@shared/entity/report/report-catalog.api';
import { ReportSharingComponent } from '@shared/entity/report/report-sharing.component';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

/** How long the search waits after the last key before it asks the server. */
const SEARCH_DELAY_MS = 300;

/**
 * The reports catalog (plan 10/10, the form and report builder; ADR-0036): the reports and widgets the person may open
 * — their own and those a role shares with them — found by a part of the name, the list they report on and ownership.
 * A report opens on its list's report tab, where the owner also changes it; the owner shares it from here. The server
 * checks the definition, the entity's rights and the reader's scope on every read; the page only links.
 */
@Component({
  selector: 'app-reports-catalog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    RouterLink,
    TranslatePipe,
    ReportSharingComponent,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTSelectComponent,
    SMTTagComponent,
    UiPageHeaderComponent,
  ],
  templateUrl: './reports-catalog.component.html',
  styleUrl: './reports-catalog.component.css',
})
export class ReportsCatalogComponent {
  private readonly api = inject(ReportCatalogApi);
  private readonly i18n = inject(I18nService);
  private readonly navigation = inject(NavigationService);
  private readonly permissions = inject(PermissionService);
  private readonly destroyRef = inject(DestroyRef);

  /** The search as typed; it is asked once typing stops. */
  readonly search = signal('');
  readonly listCode = signal<string | null>(null);
  readonly owner = signal<'mine' | 'shared' | null>(null);
  private readonly asked = signal('');

  readonly filter = computed<ReportCatalogFilter>(() => ({
    q: this.asked(),
    listCode: this.listCode(),
    owner: this.owner(),
    kind: 'report',
  }));

  readonly entityOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return this.navigation
      .entityItems()
      .map((item) => ({ id: item.code, label: this.i18n.translate(item.labelKey) }))
      .sort((a, b) => a.label.localeCompare(b.label));
  });
  readonly ownerOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return [
      { id: 'mine', label: this.i18n.translate('reports.catalog.owner_mine') },
      { id: 'shared', label: this.i18n.translate('reports.catalog.owner_shared') },
    ];
  });

  readonly pager = new KeysetPager<ReportViewItem>(
    (cursor, limit) => this.api.search(untracked(this.filter), cursor, limit),
    { destroyRef: this.destroyRef },
  );

  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    effect(() => {
      this.filter();
      untracked(() => this.pager.first());
    });
    this.destroyRef.onDestroy(() => clearTimeout(this.searchTimer));
  }

  setSearch(value: unknown): void {
    this.search.set(value === null || value === undefined ? '' : String(value));
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.asked.set(this.search().trim()), SEARCH_DELAY_MS);
  }

  setList(value: unknown): void {
    this.listCode.set(typeof value === 'string' && value ? value : null);
  }

  setOwner(value: unknown): void {
    this.owner.set(value === 'mine' || value === 'shared' ? value : null);
  }

  /** The words of the list a report reads: its menu name, else its code. */
  entityName(report: ReportViewItem): string {
    this.i18n.currentLang();
    const item = this.navigation.entityItems().find((candidate) => candidate.code === report.entity);
    return item ? this.i18n.translate(item.labelKey) : report.entity;
  }

  /** The query of the link that opens a report on its list's report tab. */
  openParams(report: ReportViewItem): Record<string, string> {
    return { mode: 'report', report: String(report.id) };
  }

  /** Whether the person keeps the report's mailings (ADR-0045): any report with "all", their own with "own". */
  mayMail(report: ReportViewItem): boolean {
    return (
      this.permissions.hasPermission('report.mailings', 'manage') ||
      (report.owned && this.permissions.hasPermission('report.mailings', 'own'))
    );
  }

  sharingPath(report: ReportViewItem): string {
    return `/list-views/${encodeURIComponent(report.listCode)}/${report.id}`;
  }

  /** A changed sharing reads the page again: the report's flag and revision follow it. */
  shared(): void {
    this.pager.reload();
  }
}
