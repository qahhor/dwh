import { computed, inject, type Signal } from '@angular/core';
import type { FormMeta } from '@core/models/form-meta.models';
import { I18nService } from '@core/services/i18n.service';
import { NavigationService } from '@core/services/navigation.service';
import type { EntityRecord } from '../entities.api';

/** An entity code as the server accepts it (ADR-0032 6.1): lower-case words joined by dots, at least one dot. */
export const ENTITY_CODE = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;

/** A record id in the route: digits only, so `new` and anything else never reach the server as an id. */
export function recordIdOf(value: string | null | undefined): number | null {
  return value && /^\d{1,18}$/.test(value) ? Number(value) : null;
}

/**
 * The entity's name on its general screen: the label of its menu item (`GET /entities/menu`, ADR-0032 7.1), read
 * again when the language changes; an entity without a menu item is named by its code.
 */
export function entityTitle(code: Signal<string>): Signal<string> {
  const navigation = inject(NavigationService);
  const i18n = inject(I18nService);
  return computed(() => {
    i18n.currentLang();
    const item = navigation.entityItems().find((candidate) => candidate.code === code());
    return item ? i18n.translate(item.labelKey) : code();
  });
}

/**
 * What names a record on its page and in a confirmation: the first filled text field of the form's layout (a title,
 * a name, a number), otherwise its id.
 */
export function recordName(meta: FormMeta, record: EntityRecord, fallback: (id: number) => string): string {
  const byKey = new Map(meta.fields.map((field) => [field.key, field]));
  for (const key of meta.layout.flatMap((section) => section.fields)) {
    const field = byKey.get(key);
    const value = field && !field.attribute && field.type === 'text' ? record[key] : null;
    if (typeof value === 'string' && value.trim() !== '') return value.trim();
  }
  return fallback(record.id);
}

/** Whether a failed request was a 404: the entity is unknown or not the viewer's, or the record is out of scope. */
export function isNotFound(error: unknown): boolean {
  // A resource wraps a failure that is not an Error (the API's problem) and keeps it as the cause.
  const cause = error instanceof Error && error.cause !== undefined ? error.cause : error;
  return typeof cause === 'object' && cause !== null && (cause as { status?: unknown }).status === 404;
}
