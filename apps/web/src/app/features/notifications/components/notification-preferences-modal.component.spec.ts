import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { NotificationPrefItem } from '@core/models/notification.models';
import { inScreen } from '@testing/in-screen';
import { NotificationPreferencesModalComponent } from './notification-preferences-modal.component';

const EVENTS = [
  'task_assigned',
  'task_observer',
  'task_status',
  'task_deadline',
  'task_deadline_reminder',
  'task_member_removed',
];
const CHANNELS = ['in_app', 'email', 'telegram'];

async function render(initialPreferences: NotificationPrefItem[] = []) {
  const fixture = TestBed.createComponent(NotificationPreferencesModalComponent);
  fixture.componentRef.setInput('initialPreferences', initialPreferences);
  const saved: NotificationPrefItem[][] = [];
  let closes = 0;
  fixture.componentInstance.save.subscribe((items) => saved.push(items));
  fixture.componentInstance.close.subscribe(() => closes++);
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  const screen = inScreen(fixture.nativeElement);
  /** The channel checkboxes of each event row, in the order In-App, Email, Telegram. */
  const grid = () =>
    (Array.from(screen.querySelectorAll('.pref-table tbody tr')) as HTMLElement[]).map(
      (row) => Array.from(row.querySelectorAll('[role="checkbox"]')) as HTMLElement[],
    );
  const button = (text: string) =>
    (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (candidate) => candidate.textContent?.trim() === text,
    )!;
  return { fixture, screen, grid, button, settle, saved, closes: () => closes };
}

const enabled = (items: NotificationPrefItem[], eventType: string, channel: string) =>
  items.find((item) => item.eventType === eventType && item.channel === channel)?.isEnabled;

describe('NotificationPreferencesModalComponent', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());
  });

  it('opens as a titled dialog with a table of six event types by three channels', async () => {
    const { screen, grid } = await render();

    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Настройки уведомлений');
    expect(screen.querySelector('table.pref-table').getAttribute('aria-label')).toBe('Настройки уведомлений');
    expect(
      (Array.from(screen.querySelectorAll('.pref-table th')) as HTMLElement[]).map((th) =>
        th.textContent?.replace(/notifications|mail|send/, '').trim(),
      ),
    ).toEqual(['Тип события', 'In-App', 'Email', 'Telegram']);
    expect(grid()).toHaveLength(6);
    expect(grid().every((row) => row.length === 3)).toBe(true);
  });

  it('has every channel on by default and off where the saved preferences turned it off', async () => {
    const { grid } = await render([{ eventType: 'task_status', channel: 'email', isEnabled: false }]);

    const checked = grid().map((row) => row.map((box) => box.getAttribute('aria-checked')));
    expect(checked[2]).toEqual(['true', 'false', 'true']);
    expect(checked.filter((_row, index) => index !== 2).flat()).not.toContain('false');
  });

  it('saves the choice for every event and channel, with the boxes the person flipped', async () => {
    const { grid, button, settle, saved } = await render([
      { eventType: 'task_status', channel: 'email', isEnabled: false },
    ]);

    grid()[0][2].click();
    grid()[2][1].click();
    await settle();
    button('Сохранить').click();

    expect(saved).toHaveLength(1);
    const items = saved[0];
    expect(items).toHaveLength(EVENTS.length * CHANNELS.length);
    expect(enabled(items, 'task_assigned', 'telegram')).toBe(false);
    expect(enabled(items, 'task_status', 'email')).toBe(true);
    expect(enabled(items, 'task_deadline', 'in_app')).toBe(true);
  });

  it('asks to close from the cancel button and the close button', async () => {
    const { screen, button, closes } = await render();

    button('Отмена').click();
    (screen.querySelector('.smt-modal__close') as HTMLButtonElement).click();

    expect(closes()).toBe(2);
  });

  it('cannot be closed while saving: cancel is locked and there is no close button', async () => {
    const { fixture, screen, button, settle } = await render();

    fixture.componentRef.setInput('isSaving', true);
    await settle();

    expect(button('Отмена').disabled).toBe(true);
    expect(screen.querySelector('.smt-modal__close')).toBeNull();
  });
});
