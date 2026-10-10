import { PACKAGED_RUSSIAN } from '../app/core/i18n/packaged-russian';

/** Russian text of a key, with the plural form of a numeric `count` as I18nService.translate picks it. */
export function translateTest(key: string, params?: Record<string, string | number>): string {
  const count = params?.['count'];
  const plural =
    typeof count === 'number' ? PACKAGED_RUSSIAN[`${key}.${new Intl.PluralRules('ru-RU').select(count)}`] : undefined;
  const template = plural ?? PACKAGED_RUSSIAN[key] ?? key;
  if (!params) return template;
  return template.replace(/\{([a-zA-Z0-9_]+)\}/g, (placeholder: string, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : placeholder,
  );
}
