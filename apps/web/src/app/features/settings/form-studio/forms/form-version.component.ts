import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  HostListener,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, Subject } from 'rxjs';
import type { RecordNavigationPage } from '@core/guards/record-navigation.guard';
import type { FormValues } from '@core/models/form-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTEntityFormComponent } from '@shared/entity/smt-entity-form.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import type { StudioLanguage } from '../form-studio.models';
import { NewFormFieldComponent } from '../new-form/new-form-field.component';
import { NewFormLabelsComponent } from '../new-form/new-form-labels.component';
import { NewFormRulesComponent } from '../new-form/new-form-rules.component';
import { NewFormLinesComponent } from '../new-form/new-form-lines.component';
import { NewFormNumberingComponent } from '../new-form/new-form-numbering.component';
import { NewFormWorkflowComponent } from '../new-form/new-form-workflow.component';
import { documentContext } from '../new-form/new-form-document';
import { NewFormLayoutComponent, type LayoutChange } from '../new-form/new-form-layout.component';
import { StudioTargetsStore } from '../studio-targets.store';
import { SIMPLE_FORM_SCOPE_KEYS, SYSTEM_TITLE, titleOf } from '../new-form/new-form.models';
import { simpleFormPreview } from '../new-form/new-form-preview';
import type {
  SimpleField,
  SimpleFormLines,
  SimpleFormMenu,
  SimpleFormNumbering,
  SimpleFormRule,
  SimpleFormWorkflow,
} from '../new-form/new-form.models';
import { MENU_ICONS, shownFields, VERSION_MAX_FIELDS, VERSION_MAX_SHOWN_FIELDS } from './form-version.rules';
import { FormVersionStore } from './form-version.store';

const DEFAULT_MENU: SimpleFormMenu = { icon: 'box', section: 'workspace', order: 100 };

/**
 * The editor of a new version of a live form (ADR-0041): the new-form editor seeded from the active version, where
 * a stored field keeps its key and type and is hidden instead of removed; the changes the server would refuse are
 * listed before anything is sent.
 */
@Component({
  selector: 'app-form-version',
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
    NewFormRulesComponent,
    NewFormLinesComponent,
    NewFormNumberingComponent,
    NewFormWorkflowComponent,
  ],
  providers: [FormVersionStore, StudioTargetsStore],
  templateUrl: './form-version.component.html',
  styleUrl: './form-version.component.css',
})
export class FormVersionComponent implements OnInit, RecordNavigationPage {
  readonly store = inject(FormVersionStore);
  readonly targets = inject(StudioTargetsStore);
  private readonly route = inject(ActivatedRoute);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);
  /** The code of the form, from the route. */
  readonly code = signal('');
  readonly values = signal<FormValues>({});

  readonly menu = computed(() => this.store.draft()?.entity.menu ?? DEFAULT_MENU);
  /** Whether the active version is named by the system name and code, which a version keeps or goes without for good. */
  readonly named = computed(() => {
    const active = this.store.active();
    return active ? titleOf(active) === SYSTEM_TITLE : null;
  });
  /** The data scope chosen when the form was created; a version keeps it (ADR-0041). */
  readonly scopeKey = computed(() => SIMPLE_FORM_SCOPE_KEYS[this.store.draft()?.entity.scope ?? 'all']);
  readonly iconOptions = computed(() => {
    this.i18n.currentLang();
    return MENU_ICONS.map((icon) => ({ id: icon, label: this.i18n.translate(`studio.version.icons.${icon}`) }));
  });
  readonly sectionOptions = computed(() => [
    { id: 'workspace', label: this.i18n.translate('nav.section.workspace') },
    { id: 'administration', label: this.i18n.translate('nav.section.administration') },
  ]);
  readonly canAdd = computed(() => {
    const draft = this.store.draft();
    return (
      draft !== null &&
      draft.fields.length < VERSION_MAX_FIELDS &&
      shownFields(draft).length < VERSION_MAX_SHOWN_FIELDS &&
      !this.store.busy()
    );
  });
  readonly preview = computed(() => {
    const draft = this.store.prepared()?.declaration ?? this.store.draft();
    const language = this.i18n.currentLang();
    return draft ? simpleFormPreview(draft, language === 'uz' || language === 'en' ? language : 'ru') : null;
  });

  /** What the formulas of the form read besides its fields: a document's lines, number and status (ADR-0043). */
  readonly document = computed(() => {
    const draft = this.store.draft();
    return draft ? documentContext(draft) : null;
  });
  private readonly askDiscard = discardChangesQuestion();
  private confirmation?: Subject<void>;

  ngOnInit(): void {
    this.code.set(this.route.snapshot.paramMap.get('code') ?? '');
    this.store.load(this.code());
    this.targets.load();
  }

  /** The record title and the sections and tabs (ADR-0041, format 4): compatible changes of a version. */
  applyLayout(change: LayoutChange): void {
    this.store.edit((draft) => {
      draft.entity.titleField = change.titleField;
      if (change.layout) draft.layout = change.layout;
      else delete draft.layout;
    });
  }

  /** The tables of lines of a document (ADR-0043): stored ones stay in their place. */
  applyLines(lines: SimpleFormLines[] | undefined): void {
    this.store.edit((draft) => {
      if (lines) draft.lines = lines;
      else delete draft.lines;
    });
  }

  /** The number of a document (ADR-0043): a stored pattern and period stay. */
  applyNumbering(numbering: SimpleFormNumbering | undefined): void {
    this.store.edit((draft) => {
      if (numbering) draft.entity.numbering = numbering;
      else delete draft.entity.numbering;
    });
  }

  /** The statuses of a document (ADR-0043): stored statuses stay. */
  applyWorkflow(workflow: SimpleFormWorkflow | undefined): void {
    this.store.edit((draft) => {
      if (workflow) draft.workflow = workflow;
      else delete draft.workflow;
    });
  }

  /** The validation rules of the form (ADR-0042); none leaves the declaration without them. */
  applyRules(rules: SimpleFormRule[] | undefined): void {
    this.store.edit((draft) => {
      if (rules) draft.rules = rules;
      else delete draft.rules;
    });
  }

  setTitle(language: StudioLanguage, value: string): void {
    this.store.edit((draft) => {
      draft.entity.title[language] = value;
    });
  }

  setMenu(change: Partial<SimpleFormMenu>): void {
    this.store.edit((draft) => {
      draft.entity.menu = { ...(draft.entity.menu ?? DEFAULT_MENU), ...change };
    });
  }

  setIcon(value: unknown): void {
    if (typeof value === 'string' && (MENU_ICONS as readonly string[]).includes(value)) this.setMenu({ icon: value });
  }

  setSection(value: unknown): void {
    if (value === 'workspace' || value === 'administration') this.setMenu({ section: value });
  }

  menuOrder(value: unknown): void {
    if (typeof value === 'number' && Number.isInteger(value)) this.setMenu({ order: value });
  }

  addField(): void {
    if (!this.canAdd()) return;
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
  }

  editField(index: number, field: SimpleField): void {
    this.store.edit((draft) => {
      draft.fields[index] = field;
    });
  }

  moveField(index: number, direction: number): void {
    this.store.edit((draft) => {
      const next = index + direction;
      if (next < 0 || next >= draft.fields.length) return;
      [draft.fields[index], draft.fields[next]] = [draft.fields[next], draft.fields[index]];
    });
  }

  /** Only a field added in this version is removed; a stored one is hidden instead. */
  removeField(index: number): void {
    this.store.edit((draft) => {
      const field = draft.fields[index];
      if (field && this.store.storedField(field.id) === null) draft.fields.splice(index, 1);
    });
  }

  changeText(change: { field: string; change: string }): string {
    return this.i18n.translate(`studio.version.change.${change.change}`, { field: change.field });
  }

  canLeaveRecordPage(): boolean | Observable<boolean> {
    if (this.store.publishing()) return false;
    return this.confirmDiscard();
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if ((this.store.dirty() && !this.store.activated()) || this.store.publishing()) {
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
      const answer = this.askDiscard(this.store.dirty() && !this.store.activated(), closing)
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
}
