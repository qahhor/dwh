import { Component, input } from '@angular/core';

@Component({
  selector: 'ui-badge',
  standalone: true,
  imports: [],
  template: `
    <span [class]="'badge badge-' + variant() + (dot() ? ' has-dot' : '')">
      @if (dot()) {
        <span class="dot"></span>
      }
      <ng-content></ng-content>
    </span>
  `,
  styles: [
    `
      .badge {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        padding: 2px 8px;
        border-radius: 9999px;
        font-size: 11px;
        font-weight: 500;
        line-height: 1.4;
        white-space: nowrap;
      }

      .dot {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background-color: currentColor;
      }

      .badge-active,
      .badge-success {
        background-color: var(--success-bg);
        color: var(--success-text);
      }

      .badge-passive,
      .badge-danger {
        background-color: var(--danger-bg);
        color: var(--danger-text);
      }

      .badge-warning,
      .badge-high,
      .badge-urgent {
        background-color: var(--warning-bg);
        color: var(--warning-text);
      }

      .badge-info,
      .badge-normal {
        background-color: var(--info-bg);
        color: var(--info-text);
      }

      .badge-neutral,
      .badge-low {
        background-color: var(--bg-hover);
        color: var(--text-muted);
      }
    `,
  ],
})
export class UiBadgeComponent {
  readonly variant = input<string>('neutral');
  readonly dot = input<boolean>(false);
}
