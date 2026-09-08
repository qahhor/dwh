import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { orderedTree, orgUnitKindKeys } from './org-unit-tree';
import { OrgUnit } from './org-units.models';
@Component({ selector: 'app-org-unit-tree', standalone: true, imports: [NgTemplateOutlet, TranslatePipe], templateUrl: './org-unit-tree.component.html', styleUrl: './org-unit-tree.component.css' })
export class OrgUnitTreeComponent {
  @Input() units: OrgUnit[] = [];
  @Input() selectedId: number | null = null;
  @Input() disabled = false;
  @Output() selectUnit = new EventEmitter<OrgUnit>();
  readonly kindKeys = orgUnitKindKeys;
  readonly collapsed = new Set<number>();
  get nodes() { return orderedTree(this.units); }
  toggle(id: number): void { this.collapsed.has(id) ? this.collapsed.delete(id) : this.collapsed.add(id); }
}
