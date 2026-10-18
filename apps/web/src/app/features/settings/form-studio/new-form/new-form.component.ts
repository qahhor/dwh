import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, Subject } from 'rxjs';
import type { RecordNavigationPage } from '@core/guards/record-navigation.guard';
import type { FormValues } from '@core/models/form-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTEntityFormComponent } from '@shared/entity/smt-entity-form.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { downloadStudioJson } from '../form-studio-files';
import type { StudioLanguage } from '../form-studio.models';
import { NewFormFieldComponent } from './new-form-field.component';
import { NewFormLabelsComponent } from './new-form-labels.component';
import { NewFormLayoutComponent, type LayoutChange } from './new-form-layout.component';
import { simpleFormPreview } from './new-form-preview';
import { SIMPLE_FORM_MAX_BYTES, SIMPLE_FORM_SCOPE_KEYS, SIMPLE_FORM_SCOPES, type SimpleField } from './new-form.models';
import { StudioTargetsStore } from '../studio-targets.store';
import { NewFormActivationStore } from './new-form-activation.store';
import { NewFormAssistantComponent } from './new-form-assistant.component';
import { NewFormStore } from './new-form.store';

@Component({
  selector: 'app-new-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    TranslatePipe,
    SMTEntityFormComponent,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTSelectComponent,
    UiPageHeaderComponent,
    NewFormFieldComponent,
    NewFormLabelsComponent,
    NewFormLayoutComponent,
    NewFormAssistantComponent,
  ],
  providers: [NewFormStore, NewFormActivationStore, StudioTargetsStore],
  templateUrl: './new-form.component.html',
  styleUrl: './new-form.component.css',
})
export class NewFormComponent implements RecordNavigationPage {
  readonly store = inject(NewFormStore);
  readonly activation = inject(NewFormActivationStore);
  readonly targets = inject(StudioTargetsStore);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  readonly values = signal<FormValues>({});
  readonly busy = computed(() => this.store.busy() || this.store.importing() || this.activation.busy());
  readonly deliveryOptions = computed(() => [
    { id: 'cli', label: this.i18n.translate('settings.simple_form.delivery_cli') },
    { id: 'live', label: this.i18n.translate('settings.simple_form.delivery_live') },
  ]);
  readonly scopeOptions = computed(() => {
    this.i18n.currentLang();
    return SIMPLE_FORM_SCOPES.map((id) => ({ id, label: this.i18n.translate(SIMPLE_FORM_SCOPE_KEYS[id]) }));
  });
  readonly preview = computed(() => {
    const value = this.store.prepared();
    const language = this.i18n.currentLang();
    return value
      ? simpleFormPreview(value.declaration, language === 'uz' || language === 'en' ? language : 'ru')
      : null;
  });
  private readonly askDiscard = discardChangesQuestion();
  private importOperation = 0;
  private confirmation?: Subject<void>;

  chooseDelivery(value: unknown): void {
    if (this.busy() || (value !== 'cli' && value !== 'live')) return;
    this.store.chooseDelivery(value);
    if (value === 'live') {
      this.activation.loadAvailability();
      this.targets.load();
    }
  }
  /** The data scope of a live form, chosen when it is created (ADR-0041, owner decision 1). */
  setScope(value: unknown): void {
    if (value !== 'all' && value !== 'org_unit' && value !== 'owner') return;
    this.store.edit((draft) => {
      draft.entity.scope = value;
    });
  }
  /** The record title and the sections and tabs of a live form (ADR-0041, format 4). */
  applyLayout(change: LayoutChange): void {
    this.store.edit((draft) => {
      draft.entity.titleField = change.titleField;
      if (change.layout) draft.layout = change.layout;
      else delete draft.layout;
    });
  }
  changeIdentifier(target: 'module' | 'entity', value: unknown): void {
    this.store.edit((draft) => {
      if (target === 'module') draft.module.code = value === null ? '' : String(value);
      else draft.entity.name = value === null ? '' : String(value);
    });
  }
  setTitle(target: 'module' | 'entity', language: StudioLanguage, value: string): void {
    this.store.edit((draft) => {
      draft[target].title[language] = value;
    });
  }
  addField(): void {
    if (this.busy() || this.store.draft().fields.length >= 20) return;
    this.store.edit((draft) => {
      let index = 1;
      while (draft.fields.some((field) => field.id === `field${index}`)) index++;
      draft.fields.push({
        id: `field${index}`,
        type: 'text',
        labels: { ru: '', uz: '', en: '' },
        required: false,
        listed: true,
        maxLength: 255,
      });
    });
    this.focusField(this.store.draft().fields.length - 1);
  }
  editField(index: number, field: SimpleField): void {
    this.store.edit((draft) => {
      draft.fields[index] = field;
    });
  }
  moveField(index: number, direction: number): void {
    const next = index + direction;
    if (this.busy() || next < 0 || next >= this.store.draft().fields.length) return;
    this.store.edit((draft) => {
      [draft.fields[index], draft.fields[next]] = [draft.fields[next], draft.fields[index]];
    });
    this.focusField(next);
  }
  removeField(index: number): void {
    if (this.busy()) return;
    this.store.edit((draft) => {
      draft.fields.splice(index, 1);
    });
    this.focusField(Math.min(index, this.store.draft().fields.length - 1));
  }
  downloadPackage(): void {
    const prepared = this.store.prepared();
    if (!prepared || this.busy()) return;
    this.download(JSON.stringify(prepared, null, 2), 'smartupcms-simple-form.package.json');
  }
  downloadDraft(): void {
    if (this.busy()) return;
    this.download(JSON.stringify(this.store.draft(), null, 2), 'smartupcms-simple-form.draft.json');
  }
  importFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.busy()) return;
    if (file.size > SIMPLE_FORM_MAX_BYTES) {
      this.store.invalidImport();
      return;
    }
    this.confirmDiscard()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((discard) => {
        if (discard && !this.busy()) void this.readFile(file);
      });
  }
  canLeaveRecordPage(): boolean | Observable<boolean> {
    return this.busy() ? false : this.confirmDiscard();
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.store.dirty() || this.activation.publishing()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  private confirmDiscard(): Observable<boolean> {
    return new Observable((observer) => {
      this.confirmation?.next();
      this.confirmation?.complete();
      const closing = new Subject<void>();
      this.confirmation = closing;
      const answer = this.askDiscard(this.store.dirty(), closing)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (value) => observer.next(value),
          error: (error) => observer.error(error),
          complete: () => observer.complete(),
        });
      return () => {
        closing.next();
        closing.complete();
        answer.unsubscribe();
        if (this.confirmation === closing) this.confirmation = undefined;
      };
    });
  }

  private download(text: string, name: string): void {
    try {
      downloadStudioJson(text, name);
      this.store.markDownloaded();
    } catch {
      this.store.downloadFailed();
    }
  }
  private focusField(index: number): void {
    afterNextRender(
      () => {
        const selector = index < 0 ? '[data-testid="new-form-add"]' : `[data-testid="new-field-${index}"] input`;
        this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus();
      },
      { injector: this.injector },
    );
  }
  private async readFile(file: File): Promise<void> {
    const operation = ++this.importOperation;
    const draft = this.store.draft();
    const delivery = this.store.delivery();
    const current = () =>
      !this.destroyRef.destroyed &&
      operation === this.importOperation &&
      this.store.draft() === draft &&
      this.store.delivery() === delivery;
    this.store.importing.set(true);
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
      if (current()) this.store.importText(text);
    } catch {
      if (current()) this.store.invalidImport();
    } finally {
      if (operation === this.importOperation) this.store.importing.set(false);
    }
  }
}
