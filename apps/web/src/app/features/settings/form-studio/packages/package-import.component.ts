import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { LookupSources } from '@shared/lookups/lookup-sources';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTMultiDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { countsLine, labelOf, outcomeTone, problemLines } from './package-words';
import {
  PackagesApi,
  type PackageMapping,
  type PackageRole,
  type SolutionImport,
  type SolutionImportItem,
  type SolutionPlaceholder,
} from './packages.api';

/**
 * An import of a solution package (ADR-0047): what applying it does to each item — created, upgraded, unchanged,
 * skipped or refused with the reasons — the roles and users of the installation it came from to map to this one's,
 * a check of the mapping and the application, which keeps everything or nothing. Mailings and automations it creates
 * stay paused until an administrator switches them on.
 */
@Component({
  selector: 'app-package-import',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    RouterLink,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTMultiDataSelectComponent,
    SMTSelectComponent,
    SMTTagComponent,
    TranslatePipe,
    UiFormActionsComponent,
    UiPageHeaderComponent,
  ],
  templateUrl: './package-import.component.html',
  styleUrls: ['./packages.component.css', './package-import.component.css'],
})
export class PackageImportComponent implements OnInit {
  readonly lookups = inject(LookupSources);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(PackagesApi);
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly destroyRef = inject(DestroyRef);

  readonly view = signal<SolutionImport | null>(null);
  readonly roles = signal<PackageRole[]>([]);
  readonly mapping = signal<PackageMapping>({});
  readonly loading = signal(true);
  readonly error = signal('');
  readonly busy = signal<'check' | 'apply' | null>(null);
  readonly failure = signal('');
  readonly mappingErrors = signal<Readonly<Record<string, string>>>({});

  readonly applied = computed(() => this.view()?.status === 'applied');
  readonly refused = computed(() => this.view()?.counts.refused ?? 0);
  readonly roleOptions = computed<SMTSelectOption<number>[]>(() =>
    this.roles()
      .filter((role) => role.state === 'A')
      .map((role) => ({ id: role.id, label: role.name })),
  );
  readonly hasAutomations = computed(() =>
    (this.view()?.items ?? []).some((item) => item.kind === 'automation' || item.kind === 'mailing'),
  );

  tone = outcomeTone;

  ngOnInit(): void {
    this.api
      .roles()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (roles) => this.roles.set(roles), error: () => this.roles.set([]) });
    this.load();
  }

  load(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.loading.set(true);
    this.api
      .get(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => this.show(view),
        error: (failure: unknown) => {
          this.loading.set(false);
          this.error.set(problemText(failure) || this.i18n.translate('studio.packages.load_failed'));
        },
      });
  }

  title(labels: SolutionPlaceholder['labels'] | undefined): string {
    return labelOf(labels, this.i18n.currentLang());
  }

  counts(): string {
    this.i18n.currentLang();
    const view = this.view();
    return view ? countsLine(view.counts, (key, params) => this.i18n.translate(key, params)) : '';
  }

  /** The reasons of a refused item and the notes of any other, as lines. */
  details(item: SolutionImportItem): string[] {
    this.i18n.currentLang();
    const translate = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    return [...problemLines(item, translate), ...item.notes.map((note) => translate(note))];
  }

  role(key: string): number | null {
    return this.mapping()[key]?.[0] ?? null;
  }

  users(key: string): number[] {
    return this.mapping()[key] ?? [];
  }

  setRole(key: string, id: number | null): void {
    this.set(key, id === null ? [] : [id]);
  }

  setUsers(key: string, ids: readonly number[]): void {
    this.set(key, [...ids]);
  }

  /** Checks the mapping by applying the package in a transaction that rolls back. */
  check(): void {
    this.run(true);
  }

  /** Applies the package after a confirmation: every item or none. */
  apply(): void {
    if (this.busy()) return;
    this.modal
      .confirm({
        message: this.i18n.translate('studio.packages.apply_confirm', { name: this.title(this.view()?.title) }),
      })
      .subscribe((confirmed) => {
        if (confirmed) this.run(false);
      });
  }

  private run(dryRun: boolean): void {
    const view = this.view();
    if (!view || this.busy()) return;
    this.busy.set(dryRun ? 'check' : 'apply');
    this.failure.set('');
    this.mappingErrors.set({});
    this.api
      .apply(view.id, this.mapping(), dryRun)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.busy.set(null);
          this.show(result);
          if (!dryRun) this.toast.success(this.i18n.translate('studio.packages.applied'));
        },
        error: (failure: unknown) => {
          this.busy.set(null);
          const fields = problemFieldErrors(failure);
          const errors: Record<string, string> = {};
          for (const [field, message] of Object.entries(fields.fields)) {
            errors[field.startsWith('mapping.') ? field.slice('mapping.'.length) : field] = message;
          }
          this.mappingErrors.set(errors);
          this.failure.set(problemText(failure) || this.i18n.translate('studio.packages.action_failed'));
          this.load();
        },
      });
  }

  private show(view: SolutionImport): void {
    this.loading.set(false);
    this.view.set(view);
    if (!Object.keys(this.mapping()).length) this.mapping.set({ ...view.mapping });
  }

  private set(key: string, ids: number[]): void {
    this.mappingErrors.update((errors) => {
      const { [key]: _dropped, ...rest } = errors;
      return rest;
    });
    this.mapping.update((mapping) => ({ ...mapping, [key]: ids }));
  }
}
