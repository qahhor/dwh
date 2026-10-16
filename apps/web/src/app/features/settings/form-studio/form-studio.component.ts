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
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import type { RecordNavigationPage } from '@core/guards/record-navigation.guard';
import type { FormValues } from '@core/models/form-meta.models';
import { fieldLabel } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTEntityFormComponent } from '@shared/entity/smt-entity-form.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { FormStudioLabelsComponent } from './form-studio-labels.component';
import { studioPreviewMeta } from './form-studio-layout';
import {
  STUDIO_MAX_BYTES,
  type FormLayoutField,
  type FormLayoutSection,
  type StudioLabels,
  type StudioLanguage,
} from './form-studio.models';
import { FormStudioStore } from './form-studio.store';
import { FormStudioLifecycleStore } from './form-studio-lifecycle.store';
import { FormStudioVersionsStore } from './form-studio-versions.store';
import { FormStudioLifecycleComponent } from './form-studio-lifecycle.component';

@Component({
  selector: 'app-form-studio',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormStudioLifecycleComponent,
    RouterLink,
    TranslatePipe,
    UiPageHeaderComponent,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTSelectComponent,
    SMTEntityFormComponent,
    FormStudioLabelsComponent,
  ],
  providers: [FormStudioStore, FormStudioVersionsStore, FormStudioLifecycleStore],
  templateUrl: './form-studio.component.html',
  styleUrl: './form-studio.component.css',
})
export class FormStudioComponent implements OnInit, RecordNavigationPage {
  readonly store = inject(FormStudioStore);
  readonly lifecycle = inject(FormStudioLifecycleStore);
  readonly versions = inject(FormStudioVersionsStore);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly values = signal<FormValues>({
    code: 'demo',
    name: '',
    unit: 'pcs',
    price: { amount: '100', currency: 'UZS' },
  });
  private readonly importing = signal(false);

  readonly busy = computed(
    () => this.store.busy() || this.lifecycle.busy() || this.versions.busy() || this.importing(),
  );
  readonly language = computed<StudioLanguage>(() => {
    const current = this.i18n.currentLang();
    return current === 'en' || current === 'uz' ? current : 'ru';
  });
  readonly preview = computed(() => {
    const result = this.store.validated();
    return result ? studioPreviewMeta(result, this.language()) : null;
  });
  readonly sectionOptions = computed(
    () =>
      this.store.draft()?.sections.map((section) => ({
        id: section.id,
        label: this.sectionName(section),
      })) ?? [],
  );
  readonly canAddSection = computed(
    () =>
      (this.store.draft()?.sections.length ?? 8) < 8 &&
      !!this.store.draft()?.sections.some((section) => section.fields.length > 1),
  );

  private readonly askDiscard = discardChangesQuestion();

  ngOnInit(): void {
    this.lifecycle.load();
  }

  reloadDraft(): void {
    if (this.busy()) return;
    this.askDiscard(this.store.dirty() || this.lifecycle.serverDirty())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((discard) => {
        if (discard) this.lifecycle.load();
      });
  }

  sectionName(section: FormLayoutSection): string {
    const original = this.store.baseMeta()?.layout.find((item) => item.key === section.id);
    return (
      section.labels?.[this.language()] ||
      (original ? this.i18n.translate(original.labelKey) : this.i18n.translate('settings.form_studio.new_section'))
    );
  }

  fieldName(field: FormLayoutField): string {
    const original = this.store.baseMeta()?.fields.find((item) => item.key === field.fieldId);
    return (
      field.labels?.[this.language()] ||
      (original ? fieldLabel(original, (key) => this.i18n.translate(key)) : field.fieldId)
    );
  }

  setLabel(sectionId: string, fieldId: string | null, language: StudioLanguage, value: string): void {
    this.store.edit((draft) => {
      const section = draft.sections.find((item) => item.id === sectionId)!;
      const target = fieldId ? section.fields.find((field) => field.fieldId === fieldId)! : section;
      const labels: StudioLabels = { ru: '', uz: '', en: '', ...target.labels, [language]: value };
      if (Object.values(labels).every((label) => label === '') && (fieldId || sectionId === 'main'))
        delete target.labels;
      else target.labels = labels;
    });
  }

  moveSection(id: string, direction: number): void {
    this.store.edit((draft) => {
      const index = draft.sections.findIndex((section) => section.id === id);
      const next = index + direction;
      if (next < 0 || next >= draft.sections.length) return;
      [draft.sections[index], draft.sections[next]] = [draft.sections[next], draft.sections[index]];
    });
    this.focusGroup(`studio-section-${id}`);
  }

  moveField(sectionId: string, fieldId: string, direction: number): void {
    this.store.edit((draft) => {
      const section = draft.sections.find((item) => item.id === sectionId)!;
      const index = section.fields.findIndex((field) => field.fieldId === fieldId);
      const next = index + direction;
      if (next < 0 || next >= section.fields.length) return;
      [section.fields[index], section.fields[next]] = [section.fields[next], section.fields[index]];
    });
    this.focusGroup(`studio-field-${fieldId}`);
  }

  moveToSection(sourceId: string, fieldId: string, targetId: unknown): void {
    if (typeof targetId !== 'string' || sourceId === targetId) return;
    this.store.edit((draft) => {
      const source = draft.sections.find((section) => section.id === sourceId)!;
      const target = draft.sections.find((section) => section.id === targetId);
      if (!target) return;
      const index = source.fields.findIndex((field) => field.fieldId === fieldId);
      target.fields.push(...source.fields.splice(index, 1));
      draft.sections = draft.sections.filter((section) => section.fields.length > 0);
    });
    this.focusGroup(`studio-field-${fieldId}`);
  }

  addSection(): void {
    if (!this.canAddSection()) return;
    let addedId = '';
    this.store.edit((draft) => {
      const source = draft.sections.find((section) => section.fields.length > 1)!;
      let index = 1;
      while (draft.sections.some((section) => section.id === `section_${index}`)) ++index;
      addedId = `section_${index}`;
      draft.sections.push({
        id: addedId,
        labels: { ru: '', uz: '', en: '' },
        fields: [source.fields.pop()!],
      });
    });
    this.focusGroup(`studio-section-${addedId}`, true);
  }

  removeSection(id: string): void {
    let targetId = '';
    this.store.edit((draft) => {
      if (draft.sections.length < 2) return;
      const index = draft.sections.findIndex((section) => section.id === id);
      const target = draft.sections[index === 0 ? 1 : index - 1];
      targetId = target.id;
      target.fields.push(...draft.sections[index].fields);
      draft.sections.splice(index, 1);
    });
    if (targetId) this.focusGroup(`studio-section-${targetId}`);
  }

  importFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (file.size > STUDIO_MAX_BYTES) {
      this.store.invalidImport();
      return;
    }
    this.askDiscard(this.store.dirty() || this.lifecycle.serverDirty())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((discard) => {
        if (discard) void this.readImport(file);
      });
  }

  exportFile(): void {
    const text = this.store.exportText();
    if (text === null) return;
    let url: string | undefined;
    try {
      url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'smartupcms-example-products.layout.json';
      anchor.click();
      this.store.markExported();
    } catch {
      this.store.exportFailed();
    } finally {
      if (url) URL.revokeObjectURL(url);
    }
  }

  canLeaveRecordPage(): boolean | Observable<boolean> {
    return this.busy() ? false : this.askDiscard(this.store.dirty() || this.lifecycle.serverDirty());
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (!this.store.dirty() && !this.lifecycle.serverDirty()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  private focusGroup(testId: string, input = false): void {
    const previous = this.host.nativeElement.ownerDocument.activeElement as HTMLElement | null;
    afterNextRender(
      () => {
        const group = this.host.nativeElement.querySelector(`[data-testid="${testId}"]`);
        if (!input && previous && group?.contains(previous) && !previous.matches(':disabled')) previous.focus();
        else group?.querySelector<HTMLElement>(input ? 'input' : 'button:not([disabled]), input')?.focus();
      },
      { injector: this.injector },
    );
  }

  private async readImport(file: File): Promise<void> {
    this.importing.set(true);
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
      if (!this.destroyRef.destroyed) this.store.importText(text);
    } catch {
      if (!this.destroyRef.destroyed) this.store.invalidImport();
    } finally {
      this.importing.set(false);
    }
  }
}
