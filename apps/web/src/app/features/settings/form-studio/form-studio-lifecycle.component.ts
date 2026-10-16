import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { FormStudioLifecycleStore } from './form-studio-lifecycle.store';
import { FormStudioVersionsStore } from './form-studio-versions.store';
import { FormStudioStore } from './form-studio.store';

@Component({
  selector: 'app-form-studio-lifecycle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTAlertComponent, SMTButtonComponent],
  templateUrl: './form-studio-lifecycle.component.html',
  styleUrl: './form-studio-lifecycle.component.css',
})
export class FormStudioLifecycleComponent {
  readonly i18n = inject(I18nService);
  readonly lifecycle = inject(FormStudioLifecycleStore);
  readonly versions = inject(FormStudioVersionsStore);
  readonly editor = inject(FormStudioStore);
  readonly blocked = input(false);
  readonly reload = output<void>();

  verifyFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) void this.versions.verifyFile(file);
  }
}
