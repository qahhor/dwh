import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { form, FormField, pattern } from '@angular/forms/signals';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { problemText } from '@shared/ui/problem-text';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTTabBarComponent, type SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { PackageExportComponent } from './package-export.component';
import { countsLine, labelOf } from './package-words';
import { PackagesApi, type SolutionImportSummary, type SolutionTemplate } from './packages.api';

/** The tabs of the page. */
export type PackagesTab = 'templates' | 'import' | 'export' | 'history';

const TABS: readonly PackagesTab[] = ['templates', 'import', 'export', 'history'];
/** The largest package file the server reads (ADR-0047). */
export const PACKAGE_MAX_BYTES = 2 * 1024 * 1024;
const SUFFIX = /^_[a-z0-9]{1,5}$/;

/**
 * Solution packages and templates in Studio (ADR-0047): the template gallery, where a solution is created from a
 * template; the upload of a package file; the export of chosen forms with what they use; the history of imports.
 * Creating from a template and uploading only check the package: the import page shows what applying it would do,
 * takes the mapping of its roles and users and applies it.
 */
@Component({
  selector: 'app-packages',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormField,
    PackageExportComponent,
    RouterLink,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTTabBarComponent,
    SMTTagComponent,
    TranslatePipe,
    UiFormActionsComponent,
    UiPageHeaderComponent,
  ],
  templateUrl: './packages.component.html',
  styleUrl: './packages.component.css',
})
export class PackagesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(PackagesApi);
  private readonly i18n = inject(I18nService);
  private readonly permissions = inject(PermissionService);
  private readonly destroyRef = inject(DestroyRef);

  readonly tab = signal<PackagesTab>('templates');
  readonly templates = signal<SolutionTemplate[]>([]);
  readonly imports = signal<SolutionImportSummary[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly busy = signal<string | null>(null);
  readonly file = signal<File | null>(null);
  readonly fileError = signal('');
  readonly uploadError = signal('');
  readonly uploadModel = signal({ suffix: '' });

  readonly mayImport = computed(() => this.permissions.hasPermission('studio.packages', 'import'));
  readonly mayExport = computed(() => this.permissions.hasPermission('studio.packages', 'export'));
  readonly tabs = computed<SMTTabItem<PackagesTab>[]>(() => {
    this.i18n.currentLang();
    return TABS.filter((tab) => (tab === 'export' ? this.mayExport() : this.mayImport())).map((tab) => ({
      value: tab,
      label: this.i18n.translate(`studio.packages.tab.${tab}`),
      panelId: `packages-panel-${tab}`,
      id: `packages-tab-${tab}`,
    }));
  });

  readonly uploadForm = form(this.uploadModel, (path) => {
    pattern(path.suffix, SUFFIX);
  });

  ngOnInit(): void {
    const wanted = this.route.snapshot.queryParamMap.get('tab') as PackagesTab | null;
    const first = this.tabs()[0]?.value ?? 'templates';
    this.choose(wanted && this.tabs().some((tab) => tab.value === wanted) ? wanted : first, false);
  }

  choose(tab: PackagesTab, navigate = true): void {
    this.tab.set(tab);
    if (navigate) void this.router.navigate([], { queryParams: { tab }, replaceUrl: true });
    if (tab === 'templates' && !this.templates().length) this.loadTemplates();
    if (tab === 'history') this.loadImports(null);
  }

  title(labels: SolutionTemplate['title']): string {
    return labelOf(labels, this.i18n.currentLang());
  }

  counts(item: SolutionImportSummary): string {
    this.i18n.currentLang();
    return countsLine(item.counts, (key, params) => this.i18n.translate(key, params));
  }

  /** What a template holds, as one line. */
  holds(template: SolutionTemplate): string {
    this.i18n.currentLang();
    return this.i18n.translate('studio.packages.template_holds', {
      forms: template.forms,
      lists: template.lists,
      reports: template.reports,
      mailings: template.mailings,
      automations: template.automations,
    });
  }

  /** Checks the template's package and opens the import, where applying it creates the solution. */
  create(template: SolutionTemplate): void {
    this.busy.set(template.code);
    this.error.set('');
    this.api
      .fromTemplate(template.code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (created) => void this.router.navigate(['/settings/form-studio/packages/imports', created.id]),
        error: (failure: unknown) => {
          this.busy.set(null);
          this.error.set(problemText(failure) || this.i18n.translate('studio.packages.action_failed'));
        },
      });
  }

  pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const chosen = input.files?.[0] ?? null;
    input.value = '';
    this.fileError.set('');
    this.uploadError.set('');
    if (chosen && chosen.size > PACKAGE_MAX_BYTES) {
      this.file.set(null);
      this.fileError.set(this.i18n.translate('studio.packages.file_too_large'));
      return;
    }
    this.file.set(chosen);
  }

  suffixError(): string {
    const field = this.uploadForm.suffix();
    return field.touched() && field.errors().length ? this.i18n.translate('studio.packages.suffix_invalid') : '';
  }

  /** Reads the file, uploads the package and opens its import; nothing changes until the import is applied. */
  upload(): void {
    if (this.busy()) return;
    markSMTFormFieldsTouched(this.uploadForm);
    const file = this.file();
    if (!file) this.fileError.set(this.i18n.translate('studio.packages.file_required'));
    if (!file || !this.uploadForm.suffix().valid()) return;
    this.busy.set('upload');
    this.uploadError.set('');
    void file.text().then((text) => {
      let pack: unknown;
      try {
        pack = JSON.parse(text);
      } catch {
        this.busy.set(null);
        this.fileError.set(this.i18n.translate('studio.packages.file_not_json'));
        return;
      }
      this.api
        .upload(pack, this.uploadModel().suffix)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (created) => void this.router.navigate(['/settings/form-studio/packages/imports', created.id]),
          error: (failure: unknown) => {
            this.busy.set(null);
            this.uploadError.set(problemText(failure) || this.i18n.translate('studio.packages.action_failed'));
          },
        });
    });
  }

  loadImports(cursor: string | null): void {
    this.loading.set(true);
    this.error.set('');
    this.api
      .imports(cursor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.loading.set(false);
          this.imports.update((known) => (cursor ? [...known, ...page.items] : page.items));
          this.nextCursor.set(page.hasMore ? page.nextCursor : null);
        },
        error: (failure: unknown) => {
          this.loading.set(false);
          this.error.set(problemText(failure) || this.i18n.translate('studio.packages.load_failed'));
        },
      });
  }

  private loadTemplates(): void {
    this.loading.set(true);
    this.api
      .templates()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (templates) => {
          this.loading.set(false);
          this.templates.set(templates);
        },
        error: (failure: unknown) => {
          this.loading.set(false);
          this.error.set(problemText(failure) || this.i18n.translate('studio.packages.load_failed'));
        },
      });
  }
}
