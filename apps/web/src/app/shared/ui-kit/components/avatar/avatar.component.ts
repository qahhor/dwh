/* Our code, after the idea of the kit's `smt-avatar` (smartup-ui-kit@6472beb,
 * components/avatar). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it carried an image viewer, status dots and a
 * palette per theme; this application had eight hand-made avatars instead,
 * each with its own initials rule and hard-coded colours, some below AA.
 *
 * This one shows a person's photo, or their initials on one of six token
 * tones picked from the name, so the same person has the same colour on
 * every screen and both themes stay readable. It is decorative by default —
 * the name is written next to it almost everywhere; `smtLabelled` makes it
 * an image named by the person when it stands alone. A photo that fails to
 * load falls back to the initials. */
import { booleanAttribute, ChangeDetectionStrategy, Component, computed, effect, input, signal, ViewEncapsulation } from '@angular/core';

export type SMTAvatarSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

/** Tones match token pairs checked by the contrast audit in both themes. */
export const SMT_AVATAR_TONES = 6;

/** Up to two initials: first letters of the first and the last word. */
export function avatarInitials(name: string | null | undefined): string {
  const words = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  const first = [...words[0]][0] ?? '';
  const last = words.length > 1 ? [...words[words.length - 1]][0] ?? '' : '';
  return (first + last).toLocaleUpperCase();
}

/** The same name gets the same tone everywhere. */
export function avatarTone(name: string | null | undefined): number {
  let hash = 0;
  for (const char of (name ?? '').trim().toLocaleLowerCase()) hash = (hash * 31 + char.codePointAt(0)!) | 0;
  return Math.abs(hash) % SMT_AVATAR_TONES;
}

@Component({
  selector: 'smt-avatar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './avatar.scss',
  host: {
    class: 'smt-avatar',
    '[class]': 'hostClass()',
    '[attr.role]': "labelled() ? 'img' : null",
    '[attr.aria-label]': 'labelled() ? name() : null',
    '[attr.aria-hidden]': "labelled() ? null : 'true'",
    '[attr.title]': 'labelled() ? name() : null',
  },
  // One line: no spaces around the initials in the text of the row around it.
  template: `@if (showImage()) {<img class="smt-avatar__image" [src]="imageUrl()" alt="" (error)="failed.set(true)" />} @else {<ng-container>{{ initials() }}</ng-container>}`,
})
export class SMTAvatarComponent {
  readonly name = input<string | null | undefined>('');

  /** A photo; the initials show until it is given and if it fails. */
  readonly imageUrl = input<string | null | undefined>(null, { alias: 'smtImageUrl' });

  readonly size = input<SMTAvatarSize>('md', { alias: 'smtSize' });

  /** Named as an image by the person's name, for an avatar with no name written beside it. */
  readonly labelled = input(false, { alias: 'smtLabelled', transform: booleanAttribute });

  protected readonly failed = signal(false);

  readonly initials = computed(() => avatarInitials(this.name()));

  readonly showImage = computed(() => !!this.imageUrl() && !this.failed());

  readonly hostClass = computed(() => `smt-avatar smt-avatar--${this.size()} smt-avatar--tone-${avatarTone(this.name())}`);

  constructor() {
    // A new photo gets a new chance to load.
    effect(() => {
      this.imageUrl();
      this.failed.set(false);
    });
  }
}
