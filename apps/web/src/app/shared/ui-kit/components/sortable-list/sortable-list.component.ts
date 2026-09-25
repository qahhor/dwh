/* Our code, after the idea of the kit's `smt-sortable-list` and
 * `smt-sortable-card` (smartup-ui-kit@6472beb, components/sortable-list,
 * sortable-card). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it left the caller to reorder CDK's container
 * data and offered dragging only; a person who cannot drag could not change
 * the order at all.
 *
 * This one keeps the order in the caller's hands and makes it reachable in
 * two ways: drag by the handle (CDK drag-drop, locked to the list's axis),
 * or the "up" and "down" buttons of each row. A move by button keeps focus
 * on the moved row's button and is announced with the new place, as the
 * column settings do. Rows the caller locks (a system record) move neither
 * way, and nothing can be dropped past them. The list emits the new order;
 * the caller saves it and hands it back.
 *
 * <smt-sortable-list [items]="types" [trackBy]="byId" [itemLabel]="nameOf" (reorder)="save($event)">
 *   <ng-template smtSortableItem let-type>{{ type.name }}</ng-template>
 *   <ng-template smtSortableActions let-type><button …>Delete</button></ng-template>
 * </smt-sortable-list> */
import {
  afterNextRender,
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  contentChild,
  Directive,
  inject,
  Injector,
  input,
  output,
  signal,
  TemplateRef,
  ViewEncapsulation,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { SMTI18nService } from '../../i18n';

/** The row's body: `let-item` and `let-index="index"`. */
@Directive({ selector: 'ng-template[smtSortableItem]', standalone: true })
export class SMTSortableItemDirective {
  readonly template = inject<TemplateRef<{ $implicit: unknown; index: number }>>(TemplateRef);
}

/** The row's own actions, after the move buttons: `let-item`. */
@Directive({ selector: 'ng-template[smtSortableActions]', standalone: true })
export class SMTSortableActionsDirective {
  readonly template = inject<TemplateRef<{ $implicit: unknown; index: number }>>(TemplateRef);
}

let nextListId = 0;

@Component({
  selector: 'smt-sortable-list',
  standalone: true,
  imports: [CdkDropList, CdkDrag, CdkDragHandle, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './sortable-list.scss',
  host: { class: 'smt-sortable-list' },
  template: `
    <div
      class="smt-sortable-list__rows"
      role="list"
      [id]="listId"
      [attr.aria-label]="ariaLabel() || null"
      cdkDropList
      cdkDropListLockAxis="y"
      [cdkDropListDisabled]="disabled()"
      [cdkDropListSortPredicate]="canDropAt"
      (cdkDropListDropped)="onDrop($event)">
      @for (item of items(); track trackBy()(item); let index = $index, first = $first, last = $last) {
        <div
          class="smt-sortable-list__row"
          role="listitem"
          cdkDrag
          [cdkDragData]="item"
          [cdkDragDisabled]="isLocked(item)"
          [class.smt-sortable-list__row--locked]="isLocked(item)">
          <span
            class="material-symbols-outlined smt-sortable-list__handle"
            aria-hidden="true"
            cdkDragHandle
            [title]="i18n.messages().sortable.handle">drag_indicator</span>
          <div class="smt-sortable-list__body">
            @if (itemTemplate(); as body) {
              <ng-container *ngTemplateOutlet="body.template; context: { $implicit: item, index: index }" />
            }
          </div>
          <div class="smt-sortable-list__actions">
            @if (!isLocked(item) && !disabled()) {
              <button
                type="button"
                class="smt-sortable-list__move"
                [attr.data-key]="keyOf(item)"
                data-move="-1"
                [disabled]="!canMove(index, -1)"
                [attr.aria-label]="i18n.messages().sortable.moveUp(itemLabel()(item))"
                (click)="move(index, -1)">
                <span class="material-symbols-outlined" aria-hidden="true">arrow_upward</span>
              </button>
              <button
                type="button"
                class="smt-sortable-list__move"
                [attr.data-key]="keyOf(item)"
                data-move="1"
                [disabled]="!canMove(index, 1)"
                [attr.aria-label]="i18n.messages().sortable.moveDown(itemLabel()(item))"
                (click)="move(index, 1)">
                <span class="material-symbols-outlined" aria-hidden="true">arrow_downward</span>
              </button>
            }
            @if (actionsTemplate(); as actions) {
              <ng-container *ngTemplateOutlet="actions.template; context: { $implicit: item, index: index }" />
            }
          </div>
        </div>
      }
    </div>
    <p class="sr-only" role="status">{{ announcement() }}</p>
  `,
})
export class SMTSortableListComponent<T> {
  readonly i18n = inject(SMTI18nService);

  private readonly injector = inject(Injector);

  readonly items = input<readonly T[]>([]);

  /** A row's identity, so a moved row keeps its DOM and focus. */
  readonly trackBy = input<(item: T) => unknown>(item => item);

  /** What a row is called in button names and announcements. */
  readonly itemLabel = input<(item: T) => string>(item => String(item));

  /** Rows that stay where they are, such as system records. */
  readonly locked = input<(item: T) => boolean>(() => false);

  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  readonly disabled = input(false, { transform: booleanAttribute });

  /** The whole list in its new order. */
  readonly reorder = output<T[]>();

  readonly itemTemplate = contentChild(SMTSortableItemDirective);

  readonly actionsTemplate = contentChild(SMTSortableActionsDirective);

  readonly announcement = signal('');

  readonly listId = `smt-sortable-list-${nextListId++}`;

  /** CDK asks before a dragged row may take an index: never onto a locked row's place. */
  readonly canDropAt = (index: number): boolean => {
    const item = this.items()[index];
    return item !== undefined && !this.isLocked(item);
  };

  isLocked(item: T): boolean {
    return this.locked()(item);
  }

  keyOf(item: T): string {
    return String(this.trackBy()(item));
  }

  /** A move by button stops at the edge and at a locked row. */
  canMove(index: number, delta: -1 | 1): boolean {
    const target = this.items()[index + delta];
    return target !== undefined && !this.isLocked(target);
  }

  move(index: number, delta: -1 | 1): void {
    if (this.disabled() || !this.canMove(index, delta)) return;
    const item = this.items()[index];
    const next = [...this.items()];
    moveItemInArray(next, index, index + delta);
    this.emit(next, item, index + delta);
    this.keepFocus(this.keyOf(item), delta);
  }

  onDrop(event: CdkDragDrop<unknown>): void {
    if (event.previousIndex === event.currentIndex) return;
    const item = this.items()[event.previousIndex];
    const next = [...this.items()];
    moveItemInArray(next, event.previousIndex, event.currentIndex);
    this.emit(next, item, event.currentIndex);
  }

  private emit(next: T[], item: T, index: number): void {
    this.reorder.emit(next);
    this.announcement.set(this.i18n.messages().sortable.moved(this.itemLabel()(item), index + 1, next.length));
  }

  /** The moved row keeps focus; at the edge its button is disabled, so focus goes to the other one. */
  private keepFocus(key: string, delta: -1 | 1): void {
    afterNextRender(() => {
      const buttons = Array.from(document.getElementById(this.listId)?.querySelectorAll<HTMLButtonElement>('[data-move]') ?? [])
        .filter(button => button.dataset['key'] === key);
      const same = buttons.find(button => button.dataset['move'] === String(delta));
      const other = buttons.find(button => button.dataset['move'] === String(-delta));
      (same && !same.disabled ? same : other)?.focus();
    }, { injector: this.injector });
  }
}
