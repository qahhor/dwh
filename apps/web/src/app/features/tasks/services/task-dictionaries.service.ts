import { Injectable, ResourceRef, WritableSignal, inject, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { QueryListMeta, enumLabel } from '@core/models/query-meta.models';
import { TaskStatus, TaskType } from '@core/models/task.models';
import { PermissionService } from '@core/services/permission.service';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { EntitiesApi, EntityRecord } from '@shared/entity/entities.api';

/** The reference entities of the task types and statuses on the general runtime (ADR-0032 8). */
export const TASK_TYPES = 'ms.task_types';
export const TASK_STATUSES = 'ms.task_statuses';

/** The rights of the reference lists: `ms.task_types` and `ms.task_statuses` (V169). */
const FORM_TYPES = 'tasks.types';
const FORM_STATUSES = 'tasks.statuses';

/**
 * The item a reorder moved: the one whose removal leaves both orders the same. The sortable list moves one item per
 * drag or key press.
 */
export function movedItem<T extends { id: number }>(before: T[], after: T[]): T | null {
  const first = after.findIndex((item, index) => item.id !== before[index]?.id);
  if (first < 0) return null;
  const without = (list: T[], id: number) => list.filter((item) => item.id !== id).map((item) => item.id);
  for (const candidate of [after[first], before[first]]) {
    if (!candidate) continue;
    const rest = without(after, candidate.id);
    if (rest.every((id, index) => id === without(before, candidate.id)[index])) return candidate;
  }
  return null;
}

/** One add form of the dictionaries dialog, as the dialog reads it. */
export interface DictionaryAddState {
  readonly saving: WritableSignal<boolean>;
  /** The server's refusal by field (`code`, `name`, `color`). */
  readonly errors: WritableSignal<Readonly<Record<string, string>>>;
  /** Items added since the page opened: each success clears the form. */
  readonly added: WritableSignal<number>;
}

export function newAddState(): DictionaryAddState {
  return { saving: signal(false), errors: signal({}), added: signal(0) };
}

@Injectable({
  providedIn: 'root',
})
export class TaskDictionariesService {
  private readonly entities = inject(EntitiesApi);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);
  private readonly permissions = inject(PermissionService);

  readonly isSettingsModalOpen = signal<boolean>(false);

  /** The add forms of the dialog: running, the server's field errors, how many items were added. */
  readonly typeAdd: DictionaryAddState = newAddState();
  readonly statusAdd: DictionaryAddState = newAddState();

  // A failed read answers with the list already on screen, so a reload that fails keeps it.
  private readonly statusesResource: ResourceRef<TaskStatus[]> = rxResource({
    defaultValue: [],
    stream: () =>
      this.canRead(FORM_STATUSES)
        ? this.entities.all(TASK_STATUSES).pipe(
            map((records) => records as unknown as TaskStatus[]),
            catchError(() => of(untracked(this.statusesResource.value))),
          )
        : of(untracked(this.statusesResource.value)),
  });
  private readonly typesResource: ResourceRef<TaskType[]> = rxResource({
    defaultValue: [],
    stream: () =>
      this.canRead(FORM_TYPES)
        ? this.entities.all(TASK_TYPES).pipe(
            map((records) => records as unknown as TaskType[]),
            catchError(() => of(untracked(this.typesResource.value))),
          )
        : of(untracked(this.typesResource.value)),
  });

  /** Writable: a new order is shown before the server confirms it. */
  readonly statuses = this.statusesResource.value;
  readonly taskTypes = this.typesResource.value;
  settingsTab: 'types' | 'statuses' = 'types';
  dictionaryDeleteTarget: { kind: 'type' | 'status'; id: number; name: string } | null = null;

  /** The service outlives the screen, so each visit asks again; the first read is already on its way. */
  loadStatuses(): void {
    this.statusesResource.reload();
  }

  loadTypes(): void {
    this.typesResource.reload();
  }

  /**
   * A viewer of tasks without the right to the reference lists still sees the statuses and types the task list names:
   * its metadata holds their codes in order and their names (ADR-0032 4.5). Colours, icons and the terminal flag come
   * only with the lists themselves.
   */
  adoptListMeta(meta: QueryListMeta): void {
    if (!this.canRead(FORM_STATUSES)) {
      this.statuses.set(fromEnum(meta, 'statusCode').map((item) => ({ ...item, terminal: false, system: true })));
    }
    if (!this.canRead(FORM_TYPES)) {
      this.taskTypes.set(
        fromEnum(meta, 'typeCode').map((item) => ({
          ...item,
          icon: 'task_alt',
          color: 'var(--primary)',
          system: true,
        })),
      );
    }
  }

  openSettingsModal(): void {
    this.isSettingsModalOpen.set(true);
  }

  /** A new type goes after the last one; the server puts it there. One request at a time. */
  handleCreateType(event: { code: string; name: string; icon: string; color: string }): void {
    if (this.typeAdd.saving()) return;
    this.addItem(
      this.typeAdd,
      this.entities.create(TASK_TYPES, { code: event.code, name: event.name, icon: event.icon, color: event.color }),
      ['code', 'name', 'color'],
      'tasks.dictionaries.type_added',
      'tasks.dictionaries.type_add_failed',
      () => this.loadTypes(),
    );
  }

  /** A new status gets its code from the server and goes after the last one. */
  handleCreateStatus(event: { name: string; color: string; terminal: boolean }): void {
    if (this.statusAdd.saving()) return;
    this.addItem(
      this.statusAdd,
      this.entities.create(TASK_STATUSES, { name: event.name, color: event.color, terminal: event.terminal }),
      ['name', 'color'],
      'tasks.dictionaries.status_added',
      'tasks.dictionaries.status_add_failed',
      () => this.loadStatuses(),
    );
  }

  /**
   * Sends one add form: a refusal about its fields goes under them (forms standard, section 5), any other is a
   * toast; a success counts in `added`, which clears the form.
   */
  private addItem(
    state: DictionaryAddState,
    request: Observable<unknown>,
    known: string[],
    addedKey: string,
    failedKey: string,
    reload: () => void,
  ): void {
    state.saving.set(true);
    state.errors.set({});
    request.subscribe({
      next: () => {
        state.saving.set(false);
        state.added.update((count) => count + 1);
        this.toast.success(this.uiI18n.translate(addedKey));
        reload();
      },
      error: (err: unknown) => {
        state.saving.set(false);
        const { fields, other } = problemFieldErrors(err, { known });
        state.errors.set(fields);
        if (Object.keys(fields).length === 0 || other.length > 0) {
          this.toast.error(other[0] ?? (problemText(err) || this.uiI18n.translate(failedKey)));
        }
      },
    });
  }

  /**
   * Asks before deleting a type or status and deletes from the dialog; a
   * refusal (the item is in use) is shown in the dialog, not as a toast.
   */
  handleDeleteDictionaryItem(target: { kind: 'type' | 'status'; id: number; name: string }): void {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    const isType = target.kind === 'type';
    this.modal
      .confirm({
        title: t('tasks.dictionaries.delete_title'),
        message: `${t('tasks.delete_dictionary_confirm', { kind: t(isType ? 'tasks.task_type_accusative' : 'tasks.status_accusative'), name: target.name })}\n${t('tasks.dictionaries.delete_in_use_warning')}`,
        yesLabel: t('common.delete'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () =>
          this.entities.remove(isType ? TASK_TYPES : TASK_STATUSES, target.id).pipe(
            tap(() => {
              if (isType) {
                this.toast.success(t('tasks.dictionaries.type_deleted'));
                this.loadTypes();
              } else {
                this.toast.success(t('tasks.dictionaries.status_deleted'));
                this.loadStatuses();
              }
            }),
          ),
        actionError: (error) =>
          problemText(error) ||
          t(isType ? 'tasks.dictionaries.type_delete_failed' : 'tasks.dictionaries.status_in_use'),
      })
      .subscribe();
  }

  handleReorderTypes(list: TaskType[]): void {
    const before = this.taskTypes();
    this.taskTypes.set(list);
    this.persistOrder(TASK_TYPES, before, list, 'tasks.dictionaries.type_order_saved', () => this.loadTypes());
  }

  handleReorderStatuses(list: TaskStatus[]): void {
    const before = this.statuses();
    this.statuses.set(list);
    this.persistOrder(TASK_STATUSES, before, list, 'tasks.dictionaries.status_order_saved', () => this.loadStatuses());
  }

  /**
   * One move per reorder (the record action `move`, ADR-0032 6.7): the moved item goes to its new place from the
   * revision on screen; the others shift on the server, so the list is read again for their new revisions.
   */
  private persistOrder<T extends { id: number; revision?: number }>(
    code: string,
    before: T[],
    after: T[],
    savedKey: string,
    reload: () => void,
  ): void {
    const moved = movedItem(before, after);
    if (!moved) return;
    const move: Observable<EntityRecord> = this.entities.action(code, moved.id, 'move', moved.revision, {
      position: after.indexOf(moved) + 1,
    });
    move.subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate(savedKey));
        reload();
      },
      error: (err) => {
        this.toast.error(problemText(err) || this.uiI18n.translate('tasks.dictionaries.reorder_failed'));
        reload();
      },
    });
  }

  private canRead(form: string): boolean {
    return this.permissions.hasPermission(form, 'view');
  }
}

/** The items of a list field of codes, in the list's order, named as the list names them. */
function fromEnum(meta: QueryListMeta, key: string): { id: number; code: string; name: string; sortOrder: number }[] {
  const field = meta.fields.find((candidate) => candidate.key === key);
  if (!field) return [];
  return field.enumValues.map((code, index) => ({
    id: -(index + 1),
    code,
    name: enumLabel(field, code, (labelKey) => labelKey),
    sortOrder: index + 1,
  }));
}
