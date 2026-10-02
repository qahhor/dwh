import { EnvironmentProviders, InjectionToken, Type, inject, makeEnvironmentProviders } from '@angular/core';

/** A right of the permission matrix (ADR-0028): a form and one of its actions. */
export interface EntityRight {
  form: string;
  action: string;
}

/**
 * A tab of the record page an entity adds (ADR-0032 7.2): its key, the catalog key of its label and the component
 * drawn in it, which gets the inputs `meta` (the entity's `form-meta`) and `record` (the record as read). With
 * `requires` the tab is offered only to a viewer who holds one of these rights; the server still decides what it
 * answers — the tab only does not offer what would be refused.
 */
export interface EntityTabOverride {
  key: string;
  labelKey: string;
  component: Type<unknown>;
  requires?: readonly EntityRight[];
}

/**
 * Small tweaks of the general entity screen by key, without a screen of its own (ADR-0032 7.2) — what `cells` of
 * `registryTableConfig` and `smtEntityField` give a screen that is written by hand:
 *
 * - `cells` — a list cell by field key: a component with the inputs `row` (the record) and `field` (`query-meta`);
 * - `fields` — a form control by field key: a component with the inputs `field` (`form-meta`), `value`, `problem`,
 *   `disabled` and `set` (a function that takes the new value);
 * - `sections` — a section of the record card by its key: a component with the inputs `meta`, `record` and `values`;
 * - `tabs` — tabs added to the record page after the platform's.
 *
 * An entity without overrides has no web file at all.
 */
export interface EntityOverrides {
  cells?: Readonly<Record<string, Type<unknown>>>;
  fields?: Readonly<Record<string, Type<unknown>>>;
  sections?: Readonly<Record<string, Type<unknown>>>;
  tabs?: readonly EntityTabOverride[];
}

interface EntityOverrideEntry {
  code: string;
  overrides: EntityOverrides;
}

const ENTITY_OVERRIDES = new InjectionToken<readonly EntityOverrideEntry[]>('ENTITY_OVERRIDES');

const NONE: EntityOverrides = {};

/**
 * The tweaks of entity `code` on its general screen, given once among the application's providers:
 * `provideEntityOverrides('sales.orders', { cells: { status: OrderStatusCell } })`. Several calls for one entity add
 * up; a later key wins.
 */
export function provideEntityOverrides(code: string, overrides: EntityOverrides): EnvironmentProviders {
  return makeEnvironmentProviders([{ provide: ENTITY_OVERRIDES, multi: true, useValue: { code, overrides } }]);
}

/** The tweaks of entity `code`, merged; none — the platform draws everything. Call in an injection context. */
export function injectEntityOverrides(): (code: string) => EntityOverrides {
  const entries = inject(ENTITY_OVERRIDES, { optional: true }) ?? [];
  return (code) => {
    const own = entries.filter((entry) => entry.code === code).map((entry) => entry.overrides);
    if (own.length === 0) return NONE;
    return own.reduce<EntityOverrides>(
      (merged, next) => ({
        cells: { ...merged.cells, ...next.cells },
        fields: { ...merged.fields, ...next.fields },
        sections: { ...merged.sections, ...next.sections },
        tabs: [...(merged.tabs ?? []), ...(next.tabs ?? [])],
      }),
      {},
    );
  };
}
