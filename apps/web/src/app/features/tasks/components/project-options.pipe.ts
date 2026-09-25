import { Pipe, PipeTransform } from '@angular/core';
import { Project } from '../../../core/models/task.models';
import { SMTSelectOption } from '../../../shared/ui-kit/components/forms/select';

/**
 * Projects as options for smt-select, so a long project list can be searched
 * by name. Pure, so the list is rebuilt only when the projects array changes.
 */
@Pipe({ name: 'projectOptions', standalone: true })
export class ProjectOptionsPipe implements PipeTransform {
  transform(projects: readonly Project[] | null | undefined): SMTSelectOption<number>[] {
    return (projects ?? []).map(project => ({ id: project.id, label: project.name }));
  }
}
