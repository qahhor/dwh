import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subject } from 'rxjs';
import { DestroyRef } from '@angular/core';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { NewFormAssistantStore } from './new-form-assistant.store';
import { NewFormStore } from './new-form.store';

@Component({
  selector: 'app-new-form-assistant',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTAlertComponent, SMTButtonComponent, SMTControlComponent, SMTTextareaComponent],
  providers: [NewFormAssistantStore],
  templateUrl: './new-form-assistant.component.html',
  styleUrl: './new-form-assistant.component.css',
})
export class NewFormAssistantComponent {
  readonly store = inject(NewFormAssistantStore);
  private readonly source = inject(NewFormStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly i18n = inject(I18nService);

  readonly confirming = signal(false);
  readonly applied = signal(false);

  readonly title = computed(() => {
    const proposal = this.store.proposal();
    const language = this.i18n.currentLang();
    return proposal?.entity.title[language === 'uz' || language === 'en' ? language : 'ru'] ?? '';
  });
  readonly json = computed(() => JSON.stringify(this.store.proposal(), null, 2));

  private readonly askDiscard = discardChangesQuestion();

  apply(): void {
    const proposal = this.store.proposal();
    if (!proposal || !this.store.canApply() || this.confirming()) return;
    this.confirming.set(true);
    const closing = new Subject<void>();
    this.askDiscard(this.source.dirty(), closing)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.confirming.set(false);
          closing.next();
          closing.complete();
        }),
      )
      .subscribe((answer) => {
        this.confirming.set(false);
        if (answer && this.store.apply(proposal)) {
          this.applied.set(true);
          afterNextRender(
            () => {
              this.host.nativeElement
                .closest('.new-form-page')
                ?.querySelector<HTMLInputElement>('[data-testid="new-module-code"]')
                ?.focus();
            },
            { injector: this.injector },
          );
        }
      });
  }
}
