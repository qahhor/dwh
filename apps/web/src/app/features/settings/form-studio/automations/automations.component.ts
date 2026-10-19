import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import type { FormMeta } from '@core/models/form-meta.models';
import { FormMetaService } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { problemText } from '@shared/ui/problem-text';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { AutomationRunsComponent } from './automation-runs.component';
import { runTone, triggerText } from './automation-words';
import { AutomationsApi, automationRequest, type Automation, type AutomationFormSummary } from './automations.api';

/**
 * The automations of live forms (ADR-0046) in Studio: of one form or of all, each with what starts it, whether it is
 * on, its author — the person it runs as — and its last run; to change, pause or switch on, run a scheduled one now,
 * look through its run log or delete. The server checks every step.
 */
@Component({
  selector: 'app-automations',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AutomationRunsComponent,
    DatePipe,
    RouterLink,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTSelectComponent,
    SMTTagComponent,
    TranslatePipe,
    UiPageHeaderComponent,
  ],
  templateUrl: './automations.component.html',
  styleUrl: './automations.component.css',
})
export class AutomationsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(AutomationsApi);
  private readonly formMeta = inject(FormMetaService);
  private readonly i18n = inject(I18nService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly destroyRef = inject(DestroyRef);

  readonly automations = signal<Automation[]>([]);
  readonly forms = signal<AutomationFormSummary[]>([]);
  readonly metas = signal<Record<string, FormMeta>>({});
  readonly form = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly busy = signal<number | null>(null);
  readonly history = signal<Automation | null>(null);

  readonly mayManage = computed(() => this.permissions.hasPermission('studio.automations', 'manage'));
  readonly formOptions = computed<SMTSelectOption<string>[]>(() =>
    this.forms().map((form) => ({ id: form.entityCode, label: this.title(form.entityCode) })),
  );
  readonly tone = runTone;

  ngOnInit(): void {
    this.form.set(this.route.snapshot.queryParamMap.get('form'));
    this.api
      .forms()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (page) => this.forms.set(page.items), error: () => this.forms.set([]) });
    this.load();
  }

  filter(form: string | null): void {
    this.form.set(form);
    void this.router.navigate([], { queryParams: { form: form ?? null }, replaceUrl: true });
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.api
      .list(this.form())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.loading.set(false);
          this.automations.set(page.items);
          page.items.forEach((automation) => this.loadMeta(automation.form));
        },
        error: (failure: unknown) => {
          this.loading.set(false);
          this.error.set(problemText(failure) || this.i18n.translate('studio.automations.load_failed'));
        },
      });
  }

  /** A form's name in the viewer's language, else its code. */
  title(code: string): string {
    const language = this.i18n.currentLang() as 'ru' | 'uz' | 'en';
    const form = this.forms().find((candidate) => candidate.entityCode === code);
    return form ? form.title[language] || form.title.ru || code : code;
  }

  trigger(automation: Automation): string {
    this.i18n.currentLang();
    return triggerText(automation.trigger, this.metas()[automation.form] ?? null, (key, params) =>
      this.i18n.translate(key, params),
    );
  }

  /** Pauses a switched-on automation or switches a paused one on, from the revision read. */
  toggle(automation: Automation): void {
    this.busy.set(automation.id);
    this.api
      .update(automation.id, automation.revision, automationRequest(automation, { active: !automation.active }))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: () => this.done(), error: (failure: unknown) => this.failed(failure) });
  }

  runNow(automation: Automation): void {
    this.busy.set(automation.id);
    this.api
      .runNow(automation.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.toast.success(this.i18n.translate('studio.automations.run_now_queued', { n: result.queued }));
          this.done();
        },
        error: (failure: unknown) => this.failed(failure),
      });
  }

  remove(automation: Automation): void {
    this.modal
      .confirm({
        message: this.i18n.translate('studio.automations.delete_confirm', { name: automation.name }),
        destructive: true,
      })
      .subscribe((confirmed) => {
        if (!confirmed) return;
        this.api
          .remove(automation.id, automation.revision)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({ next: () => this.done(), error: (failure: unknown) => this.failed(failure) });
      });
  }

  private done(): void {
    this.busy.set(null);
    this.load();
  }

  private failed(failure: unknown): void {
    this.busy.set(null);
    this.toast.error(problemText(failure) || this.i18n.translate('studio.automations.action_failed'));
    this.load();
  }

  private loadMeta(code: string): void {
    if (this.metas()[code]) return;
    this.formMeta
      .get(code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (meta) => this.metas.update((metas) => ({ ...metas, [code]: meta })),
        error: () => undefined,
      });
  }
}
