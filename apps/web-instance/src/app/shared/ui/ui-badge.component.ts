import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'ui-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span [class]="'badge badge-' + variant + (dot ? ' has-dot' : '')">
      <span *ngIf="dot" class="dot"></span>
      <ng-content></ng-content>
    </span>
  `,
  styles: [`
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

    .badge-active, .badge-success {
      background-color: var(--success-bg);
      color: var(--success);
    }

    .badge-passive, .badge-danger {
      background-color: var(--danger-bg);
      color: var(--danger);
    }

    .badge-warning, .badge-high, .badge-urgent {
      background-color: var(--warning-bg);
      color: var(--warning);
    }

    .badge-info, .badge-normal {
      background-color: var(--info-bg);
      color: var(--info);
    }

    .badge-neutral, .badge-low {
      background-color: var(--bg-hover);
      color: var(--text-muted);
    }
  `]
})
export class UiBadgeComponent {
  @Input() variant: string = 'neutral';
  @Input() dot: boolean = false;
}
