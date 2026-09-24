import { Pipe, PipeTransform } from '@angular/core';
import { User } from '../../../core/models/auth.models';
import { SMTSelectOption } from '../../../shared/ui-kit/components/forms/select';

/** Whether a user can still be chosen: not passive and not an anonymized account. */
export function isChoosableUser(user: User): boolean {
  return user.state !== 'P' && !user.name.toLowerCase().includes('deleted user');
}

/**
 * Users as options for smt-select / smt-multi-select: name, then `@login`.
 * By default only people who can be chosen, as the old user multi-select listed
 * them; `all` keeps everyone, to name chips of people already chosen.
 * Pure, so the list is rebuilt only when the users array changes.
 */
@Pipe({ name: 'userOptions', standalone: true })
export class UserOptionsPipe implements PipeTransform {
  transform(users: readonly User[] | null | undefined, all = false): SMTSelectOption<number>[] {
    return (users ?? [])
      .filter(user => all || isChoosableUser(user))
      .map(user => ({ id: user.id, label: user.name, subLabel: `@${user.login}` }));
  }
}
