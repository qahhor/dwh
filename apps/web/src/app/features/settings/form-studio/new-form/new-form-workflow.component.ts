import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTMultiSelectComponent } from '@shared/ui-kit/components/forms/multi-select';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import type { StudioLanguage } from '../form-studio.models';
import type { FormulaDocument } from '../studio-formulas.api';
import { FormulaInputComponent } from './formula-input.component';
import { NewFormLabelsComponent } from './new-form-labels.component';
import {
  MAX_STATES,
  MAX_TRANSITIONS,
  STATE_COLORS,
  type SimpleField,
  type SimpleFormState,
  type SimpleFormTransition,
  type SimpleFormWorkflow,
} from './new-form.models';

const NO_LABELS = { ru: '', uz: '', en: '' };

/** The names of a default status or transition in each language, by its catalog key. */
export type DefaultNames = (key: string) => { ru: string; uz: string; en: string };

/**
 * The statuses a new document workflow starts with (ADR-0043): draft, pending — nothing changes while it is
 * approved — approved and rejected, each final, and the transitions to submit, approve and reject, the last two refused
 * to the author and notifying the approvers, the rejection with a required comment. Named from the catalog.
 */
export function initialWorkflow(names: DefaultNames): SimpleFormWorkflow {
  const named = (code: string) => names(`studio.workflow.default_${code}`);
  return {
    states: [
      { code: 'draft', labels: named('draft'), color: 'gray' },
      { code: 'pending', labels: named('pending'), color: 'blue', lockAll: true },
      { code: 'approved', labels: named('approved'), color: 'green', terminal: true },
      { code: 'rejected', labels: named('rejected'), color: 'red', terminal: true },
    ],
    transitions: [
      { code: 'submit', labels: named('submit'), from: ['draft'], to: 'pending', comment: 'none' },
      {
        code: 'approve',
        labels: named('approve'),
        from: ['pending'],
        to: 'approved',
        comment: 'optional',
        notAuthor: true,
        notify: true,
      },
      {
        code: 'reject',
        labels: named('reject'),
        from: ['pending'],
        to: 'rejected',
        comment: 'required',
        notAuthor: true,
        notify: true,
      },
    ],
  };
}

/**
 * The statuses of a live form's document and the transitions between them (ADR-0043): the first status is where a
 * record starts, a final one leaves the record only to be read, a status may make every field read-only; each
 * transition is a record action with a right of its own (granted in the rights editor), optionally allowed only while
 * a formula holds, with a comment none, optional or required, refused to the record's author and telling the people
 * who may take it when a record reaches its status. In a new version a stored status stays and keeps its place first
 * (`stored`); the server checks the same.
 */
@Component({
  selector: 'app-new-form-workflow',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTMultiSelectComponent,
    SMTSelectComponent,
    FormulaInputComponent,
    NewFormLabelsComponent,
  ],
  templateUrl: './new-form-workflow.component.html',
  styles: [
    ':host { display: block; min-width: 0; }',
    '.form-workflow, .workflow-item { display: grid; gap: 12px; min-width: 0; }',
    '.workflow-flags, .workflow-actions { display: flex; gap: 12px; flex-wrap: wrap; }',
  ],
})
export class NewFormWorkflowComponent {
  private readonly i18n = inject(I18nService);
  readonly workflow = input<SimpleFormWorkflow | undefined>(undefined);
  /** The header fields: a transition's condition reads them. */
  readonly fields = input<readonly SimpleField[]>([]);
  /** What a condition reads besides the fields: the lines and the number. */
  readonly document = input<FormulaDocument | null>(null);
  readonly disabled = input(false);
  /** The statuses the active version stores, or none for a new form. */
  readonly stored = input<SimpleFormWorkflow | undefined>(undefined);
  readonly changed = output<SimpleFormWorkflow | undefined>();
  readonly maxStates = MAX_STATES;
  readonly maxTransitions = MAX_TRANSITIONS;
  readonly colorOptions = computed(() => {
    this.i18n.currentLang();
    return STATE_COLORS.map((id) => ({ id, label: this.i18n.translate(`studio.workflow.color_${id}`) }));
  });
  readonly commentOptions = computed(() => {
    this.i18n.currentLang();
    return (['none', 'optional', 'required'] as const).map((id) => ({
      id,
      label: this.i18n.translate(`studio.workflow.comment_${id}`),
    }));
  });
  readonly stateOptions = computed(() => {
    const language = this.i18n.currentLang() as StudioLanguage;
    return (this.workflow()?.states ?? []).map((state) => ({
      id: state.code,
      label: state.labels[language] || state.code,
    }));
  });
  /** The statuses a transition may leave: those that are not final. */
  readonly leavableOptions = computed(() => {
    const final = new Set((this.workflow()?.states ?? []).filter((state) => state.terminal).map((state) => state.code));
    return this.stateOptions().filter((option) => !final.has(option.id));
  });
  readonly conditionDocument = computed<FormulaDocument>(() => ({
    lines: this.document()?.lines ?? [],
    numbered: this.document()?.numbered ?? false,
    statuses: true,
  }));

  storedState(state: SimpleFormState): boolean {
    return (this.stored()?.states ?? []).some((candidate) => candidate.code === state.code);
  }

  toggle(on: boolean): void {
    if (this.stored()) return;
    const names: DefaultNames = (key) => ({
      ru: this.i18n.getDictionary('ru')[key] ?? key,
      uz: this.i18n.getDictionary('uz')[key] ?? key,
      en: this.i18n.getDictionary('en')[key] ?? key,
    });
    this.changed.emit(on ? initialWorkflow(names) : undefined);
  }

  addState(): void {
    const workflow = this.workflow();
    if (!workflow || workflow.states.length >= MAX_STATES) return;
    let number = workflow.states.length + 1;
    while (workflow.states.some((state) => state.code === `status${number}`)) number++;
    this.emit({
      ...workflow,
      states: [...workflow.states, { code: `status${number}`, labels: NO_LABELS, color: 'gray' }],
    });
  }

  patchState(index: number, change: Partial<SimpleFormState>): void {
    const workflow = this.workflow();
    if (!workflow?.states[index]) return;
    const states = [...workflow.states];
    states[index] = { ...states[index], ...change };
    this.emit({ ...workflow, states });
  }

  setStateLabel(index: number, language: StudioLanguage, value: string): void {
    const state = this.workflow()?.states[index];
    if (state) this.patchState(index, { labels: { ...state.labels, [language]: value } });
  }

  setColor(index: number, value: unknown): void {
    const color = STATE_COLORS.find((candidate) => candidate === value);
    if (color) this.patchState(index, { color });
  }

  setStateFlag(index: number, flag: 'terminal' | 'lockAll', on: boolean): void {
    const workflow = this.workflow();
    if (!workflow?.states[index] || (flag === 'terminal' && index === 0)) return;
    const states = [...workflow.states];
    const next = { ...states[index] };
    if (on) next[flag] = true;
    else delete next[flag];
    states[index] = next;
    this.emit({ ...workflow, states });
  }

  removeState(index: number): void {
    const workflow = this.workflow();
    const state = workflow?.states[index];
    if (!workflow || !state || index === 0 || this.storedState(state) || workflow.states.length <= 2) return;
    this.emit({
      ...workflow,
      states: workflow.states.filter((_, position) => position !== index),
      transitions: workflow.transitions
        .map((transition) => ({ ...transition, from: transition.from.filter((code) => code !== state.code) }))
        .filter((transition) => transition.to !== state.code && transition.from.length > 0),
    });
  }

  addTransition(): void {
    const workflow = this.workflow();
    if (!workflow || workflow.transitions.length >= MAX_TRANSITIONS) return;
    let number = workflow.transitions.length + 1;
    while (workflow.transitions.some((transition) => transition.code === `step${number}`)) number++;
    const [first, second] = workflow.states;
    this.emit({
      ...workflow,
      transitions: [
        ...workflow.transitions,
        { code: `step${number}`, labels: NO_LABELS, from: [first.code], to: second.code, comment: 'none' },
      ],
    });
  }

  patchTransition(index: number, change: Partial<SimpleFormTransition>): void {
    const workflow = this.workflow();
    if (!workflow?.transitions[index]) return;
    const transitions = [...workflow.transitions];
    transitions[index] = { ...transitions[index], ...change };
    this.emit({ ...workflow, transitions });
  }

  setTransitionLabel(index: number, language: StudioLanguage, value: string): void {
    const transition = this.workflow()?.transitions[index];
    if (transition) this.patchTransition(index, { labels: { ...transition.labels, [language]: value } });
  }

  setFrom(index: number, value: unknown): void {
    if (Array.isArray(value)) this.patchTransition(index, { from: value.map(String) });
  }

  setTo(index: number, value: unknown): void {
    if (typeof value === 'string' && value) this.patchTransition(index, { to: value });
  }

  setComment(index: number, value: unknown): void {
    if (value === 'none' || value === 'optional' || value === 'required')
      this.patchTransition(index, { comment: value });
  }

  setTransitionFlag(index: number, flag: 'notAuthor' | 'notify', on: boolean): void {
    const workflow = this.workflow();
    if (!workflow?.transitions[index]) return;
    const transitions = [...workflow.transitions];
    const next = { ...transitions[index] };
    if (on) next[flag] = true;
    else delete next[flag];
    transitions[index] = next;
    this.emit({ ...workflow, transitions });
  }

  setCondition(index: number, value: string | undefined): void {
    const workflow = this.workflow();
    if (!workflow?.transitions[index]) return;
    const transitions = [...workflow.transitions];
    const next = { ...transitions[index] };
    if (value) next.condition = value;
    else delete next.condition;
    transitions[index] = next;
    this.emit({ ...workflow, transitions });
  }

  removeTransition(index: number): void {
    const workflow = this.workflow();
    if (!workflow || workflow.transitions.length <= 1) return;
    this.emit({ ...workflow, transitions: workflow.transitions.filter((_, position) => position !== index) });
  }

  text(value: unknown): string {
    return value === null || value === undefined ? '' : String(value);
  }

  private emit(workflow: SimpleFormWorkflow): void {
    this.changed.emit(workflow);
  }
}
