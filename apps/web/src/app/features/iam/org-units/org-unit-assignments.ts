import { ProblemDetail } from '@core/models/common.models';
import { UserScope } from './org-units.models';

/** Pure helpers of the user's org-unit assignments and effective scope. */

/** The problem shown for an id JavaScript cannot carry exactly; no request is made for it. */
export function unsafeIdProblem(translate: (key: string) => string): ProblemDetail {
  return {
    status: 400,
    code: 'ORG_UNIT_UNSAFE_ID',
    title: translate('iam.org_units.unavailable'),
    detail: translate('iam.org_units.readonly_id'),
  };
}

/** Assignment ids without duplicates, in ascending order, so drafts compare by value. */
export function normalizedIds(ids: readonly number[]): number[] {
  return [...new Set(ids)].sort((a, b) => a - b);
}

export function sameIds(left: readonly number[], right: readonly number[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

/** The translation key naming the scope rule; ALL while the scope is unknown. */
export function scopeRuleKey(scope: UserScope | null): string {
  return `iam.data_scope.rule_${scope?.rule.toLowerCase() ?? 'all'}`;
}

/** The translation key explaining an empty list of visible units. */
export function emptyScopeKey(scope: UserScope | null): string {
  if (scope?.rule === 'ALL') return 'iam.data_scope.all_empty';
  if (scope?.rule === 'SELF') return 'iam.data_scope.self_empty';
  return 'iam.data_scope.units_empty';
}
