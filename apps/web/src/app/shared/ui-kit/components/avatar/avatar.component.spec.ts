// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTAvatarComponent, avatarInitials, avatarTone } from './avatar.component';

@Component({
  standalone: true,
  imports: [SMTAvatarComponent],
  template: `
    <smt-avatar class="own" [name]="name()" [smtImageUrl]="photo()" smtSize="lg" />
    <smt-avatar name="Aziz Karimov" smtLabelled />
  `,
})
class Host {
  readonly name = signal('Dilnoza Rahimova');
  readonly photo = signal<string | null>(null);
}

describe('avatar helpers', () => {
  it.each([
    ['Dilnoza Rahimova', 'DR'],
    ['  aziz  ', 'A'],
    ['Anna Maria Petrova', 'AP'],
    ['Ёлка', 'Ё'],
    ['', '?'],
    [null, '?'],
  ])('writes the initials of %j as %s', (name, initials) => {
    expect(avatarInitials(name)).toBe(initials);
  });

  it('gives the same name the same tone, whatever its case and spaces', () => {
    expect(avatarTone('Aziz Karimov')).toBe(avatarTone('  aziz karimov '));
    expect(avatarTone('Aziz Karimov')).toBeGreaterThanOrEqual(0);
    expect(avatarTone('Aziz Karimov')).toBeLessThan(6);
  });
});

describe('SMTAvatarComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();
    const [decorative, labelled] = Array.from(fixture.nativeElement.querySelectorAll('smt-avatar')) as HTMLElement[];
    return { fixture, decorative, labelled };
  }

  it('is decorative beside a written name, keeps the caller\'s class and shows initials on a tone', async () => {
    const { decorative } = await render();
    expect(decorative.getAttribute('aria-hidden')).toBe('true');
    expect(decorative.hasAttribute('role')).toBe(false);
    expect(decorative.textContent!.trim()).toBe('DR');
    expect(decorative.classList).toContain('own');
    expect(decorative.classList).toContain('smt-avatar--lg');
    expect(decorative.classList).toContain(`smt-avatar--tone-${avatarTone('Dilnoza Rahimova')}`);
  });

  it('is an image named by the person when it stands alone', async () => {
    const { labelled } = await render();
    expect(labelled.getAttribute('role')).toBe('img');
    expect(labelled.getAttribute('aria-label')).toBe('Aziz Karimov');
    expect(labelled.hasAttribute('aria-hidden')).toBe(false);
  });

  it('shows the photo, falls back to the initials when it fails and tries a new photo again', async () => {
    const { fixture, decorative } = await render();
    fixture.componentInstance.photo.set('/api/v1/files/1/content');
    fixture.detectChanges();
    const image = decorative.querySelector('img') as HTMLImageElement;
    expect(image.getAttribute('alt')).toBe('');
    image.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(decorative.querySelector('img')).toBeNull();
    expect(decorative.textContent!.trim()).toBe('DR');
    fixture.componentInstance.photo.set('/api/v1/files/2/content');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(decorative.querySelector('img')).not.toBeNull();
  });
});
