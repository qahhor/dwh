import { ProblemFieldErrors } from '@shared/ui/problem-fields';
import { UiFormErrorItem } from '@shared/ui/ui-form-error-summary.component';

/**
 * The task fields the create and edit dialogs draw under smt-control: the label key and the suffix of the field's id
 * (`task-create-` / `task-edit-` + suffix). A field without an id (the people pickers) is listed without a link.
 */
const TASK_FIELD_LABELS: Readonly<Record<string, { readonly label: string; readonly id?: string }>> = {
  title: { label: 'task.title', id: 'title' },
  taskType: { label: 'tasks.common.task_type', id: 'type' },
  priority: { label: 'common.priority', id: 'priority' },
  projectId: { label: 'projects.common.project', id: 'project' },
  parentTaskId: { label: 'task.parent', id: 'parent' },
  responsibleUserId: { label: 'task.responsible', id: 'responsible' },
  endTime: { label: 'tasks.editor.due_date', id: 'deadline' },
  executorUserIds: { label: 'tasks.common.co_executors' },
  observerUserIds: { label: 'tasks.common.observers' },
};

/**
 * The error summary of a task dialog (docs/guidelines/forms-ux-standard.md, section 4): the server's field errors,
 * each linked to its field, then the messages of fields the dialog does not draw.
 */
export function taskErrorSummary(
  errors: ProblemFieldErrors,
  prefix: 'task-create' | 'task-edit',
  translate: (key: string) => string,
): UiFormErrorItem[] {
  const items: UiFormErrorItem[] = Object.entries(errors.fields).map(([field, message]) => {
    const known = TASK_FIELD_LABELS[field];
    return {
      fieldId: known?.id ? `${prefix}-${known.id}` : undefined,
      label: known ? translate(known.label) : undefined,
      message,
    };
  });
  return [...items, ...errors.other.map((message) => ({ message }))];
}
