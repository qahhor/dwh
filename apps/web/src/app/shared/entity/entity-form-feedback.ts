import { effect, type ModelSignal } from '@angular/core';
import type { FormMeta, FormProblems } from '@core/models/form-meta.models';
import { fieldLabel } from '@core/services/form-meta.service';

type Translate = (key: string, params?: Record<string, string | number>) => string;

const ROW_FIELD = /^([a-z][a-zA-Z0-9]*)\[(\d+)\]\.([a-z][a-zA-Z0-9]*)$/;
const FIELD_CONTROL = [
  'input:not([type="hidden"])',
  'select',
  'textarea',
  '[role="combobox"]',
  '[role="radiogroup"]',
  '[role="switch"]',
  '[contenteditable="true"]',
].join(', ');

/** Summary links stay inside the containing form, including a document's sibling line editors. */
export function entityProblemControlId(host: HTMLElement, key: string): string | undefined {
  const row = ROW_FIELD.exec(key);
  let container: HTMLElement | undefined | null = host;
  if (row) {
    const collection = [...(host.closest('form')?.querySelectorAll<HTMLElement>('[data-collection]') ?? [])].find(
      (element) => element.dataset['collection'] === row[1],
    );
    container = [...(collection?.querySelectorAll<HTMLElement>('[data-line]') ?? [])].find(
      (element) => element.dataset['line'] === row[2],
    );
  }
  const field = [...(container?.querySelectorAll<HTMLElement>('[data-field]') ?? [])].find(
    (element) => element.dataset['field'] === (row?.[3] ?? key),
  );
  return field?.querySelector<HTMLElement>(FIELD_CONTROL)?.id || undefined;
}

/** Unknown fields keep the server message without inventing a label from the internal address. */
export function entityProblemLabel(meta: FormMeta, key: string, translate: Translate): string | undefined {
  const field = meta.fields.find((candidate) => candidate.key === key);
  if (field) return fieldLabel(field, translate);
  const row = ROW_FIELD.exec(key);
  const rowField = row
    ? meta.collections
        ?.find((collection) => collection.key === row[1])
        ?.fields.find((candidate) => candidate.key === row[3])
    : undefined;
  return row && rowField
    ? `${translate('ui.entity_lines.line', { n: Number(row[2]) + 1 })} — ${fieldLabel(rowField, translate)}`
    : undefined;
}

/** Keeps client feedback for visited fields and server feedback until that field changes. */
export class EntityFormFeedback {
  private touched = new Set<string>();
  private client = new Map<string, string>();

  constructor(
    private readonly problems: ModelSignal<FormProblems>,
    private readonly validate: () => FormProblems,
    private readonly submitted: () => boolean = () => false,
  ) {
    effect(() => this.refresh());
  }

  leave(key: string, event: FocusEvent): void {
    const container = event.currentTarget as HTMLElement;
    if (event.relatedTarget instanceof Node && container.contains(event.relatedTarget)) return;
    this.touched.add(key);
    this.refresh();
  }

  changed(key: string): void {
    const current = this.problems();
    if (current[key]) this.touched.add(key);
    const next = { ...current };
    delete next[key];
    this.client.delete(key);
    this.problems.set(next);
    this.refresh();
  }

  /** The caller changes a row's position and moves its feedback with it. */
  remap(collection: string, indexOf: (index: number) => number | null): void {
    const address = (key: string): string | null => {
      if (!key.startsWith(`${collection}[`)) return key;
      const end = key.indexOf(']');
      const index = indexOf(Number(key.slice(collection.length + 1, end)));
      return index === null ? null : `${collection}[${index}]${key.slice(end + 1)}`;
    };
    this.touched = new Set([...this.touched].map(address).filter((key) => key !== null));
    const mapped = (entries: Iterable<[string, string]>): [string, string][] =>
      [...entries].flatMap(([key, message]) => {
        const next = address(key);
        return next === null ? [] : [[next, message] as [string, string]];
      });
    this.client = new Map(mapped(this.client));
    this.problems.set(Object.fromEntries(mapped(Object.entries(this.problems()))));
  }

  private refresh(): void {
    const current = this.problems();
    const validation = this.validate();
    const next = { ...current };
    const checked = this.submitted() ? new Set([...this.touched, ...Object.keys(validation)]) : this.touched;
    for (const key of checked) {
      if (this.submitted() && current[key] === validation[key] && validation[key]) {
        this.client.set(key, validation[key]);
        this.touched.add(key);
      }
      if (current[key] && current[key] !== this.client.get(key)) continue;
      const message = validation[key];
      if (message) {
        next[key] = message;
        this.client.set(key, message);
      } else {
        delete next[key];
        this.client.delete(key);
      }
    }
    if (
      Object.keys(current).length !== Object.keys(next).length ||
      Object.entries(next).some(([key, message]) => current[key] !== message)
    )
      this.problems.set(next);
  }
}
