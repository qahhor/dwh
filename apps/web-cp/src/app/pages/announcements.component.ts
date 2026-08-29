import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Announcement, AnnouncementContent, CpApiService } from '../core/cp-api.service';
import { dt, errorText } from '../core/format';

@Component({
  selector: 'cp-announcements',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <div>
        <h1>Объявления платформы</h1>
        <p>Черновик виден только здесь. После публикации баннер получают все
           клиенты; архив снимает его.</p>
      </div>
    </div>

    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    @if (canEdit()) {
      <div class="card">
        <div class="card-head">Новое объявление</div>
        <div class="card-body">
          <form (ngSubmit)="create()">
            <div class="form-row">
              <label class="field">
                <span>Тип баннера</span>
                <select name="type" [(ngModel)]="bannerType">
                  <option value="info">Информация</option>
                  <option value="warning">Предупреждение</option>
                  <option value="critical">Критично</option>
                </select>
              </label>
              <label class="field" style="grid-column: span 2">
                <span>Заголовок</span>
                <input name="title" [(ngModel)]="title" placeholder="Плановые работы">
              </label>
            </div>
            <label class="field" style="margin-top: 14px">
              <span>Текст</span>
              <textarea name="body" [(ngModel)]="body"
                        placeholder="В субботу с 02:00 до 04:00 обновление платформы"></textarea>
            </label>
            <button class="btn" type="submit" [disabled]="busy()" style="margin-top: 14px">
              Сохранить черновик
            </button>
          </form>
        </div>
      </div>
    }

    <div class="card">
      <div class="card-head">Все объявления</div>
      <table>
        <thead>
          <tr>
            <th>Заголовок</th><th>Тип</th><th>Состояние</th>
            <th>Адресаты</th><th>Опубликовано</th><th></th>
          </tr>
        </thead>
        <tbody>
          @for (a of items(); track a.id) {
            <tr>
              <td>
                <div>{{ ru(a)?.title || '(без заголовка)' }}</div>
                <div class="sub">{{ ru(a)?.body }}</div>
              </td>
              <td><span class="badge" [class]="typeClass(a.bannerType)">{{ typeText(a.bannerType) }}</span></td>
              <td><span class="badge" [class]="stateClass(a.state)">{{ stateText(a.state) }}</span></td>
              <td>{{ a.targets }}</td>
              <td>{{ dt(a.publishedAt) }}</td>
              <td class="actions">
                @if (canEdit()) {
                  @if (a.state === 'draft') {
                    <button class="btn btn-sm" (click)="publish(a.id)" [disabled]="busy()">Опубликовать</button>
                  }
                  @if (a.state === 'published') {
                    <button class="btn btn-secondary btn-sm" (click)="archive(a.id)" [disabled]="busy()">В архив</button>
                  }
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="empty">Объявлений нет</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .sub { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
    .actions { text-align: right; white-space: nowrap; }
  `]
})
export class AnnouncementsComponent implements OnInit {
  private api = inject(CpApiService);

  items = signal<Announcement[]>([]);
  busy = signal(false);
  error = signal('');

  bannerType = 'info';
  title = '';
  body = '';

  dt = dt;

  /** Публиковать может cp-editor; cp-admin проходит везде, cp-engineer только смотрит. */
  canEdit = computed(() => {
    const roles = this.api.user()?.roles ?? [];
    return roles.includes('cp-editor') || roles.includes('cp-admin');
  });

  ngOnInit(): void {
    void this.load();
  }

  async load(): Promise<void> {
    try {
      this.items.set(await this.api.announcements());
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось загрузить объявления'));
    }
  }

  /** Русская версия текста; у объявления теоретически может не быть ни одной. */
  ru(a: Announcement): AnnouncementContent | undefined {
    return a.contents.find(c => c.language === 'ru') ?? a.contents[0];
  }

  async create(): Promise<void> {
    if (!this.title || !this.body) {
      this.error.set('Заполните заголовок и текст');
      return;
    }
    this.busy.set(true);
    try {
      await this.api.createAnnouncement(this.bannerType, this.title, this.body);
      this.title = '';
      this.body = '';
      this.error.set('');
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось сохранить объявление'));
    } finally {
      this.busy.set(false);
    }
  }

  async publish(id: number): Promise<void> {
    await this.transition(() => this.api.publishAnnouncement(id), 'Не удалось опубликовать');
  }

  async archive(id: number): Promise<void> {
    await this.transition(() => this.api.archiveAnnouncement(id), 'Не удалось отправить в архив');
  }

  private async transition(action: () => Promise<unknown>, fallback: string): Promise<void> {
    this.busy.set(true);
    try {
      await action();
      this.error.set('');
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, fallback));
    } finally {
      this.busy.set(false);
    }
  }

  typeText(t: string): string {
    return t === 'critical' ? 'критично' : t === 'warning' ? 'предупреждение' : 'информация';
  }

  typeClass(t: string): string {
    return t === 'critical' ? 'badge-danger' : t === 'warning' ? 'badge-warning' : 'badge-info';
  }

  stateText(s: string): string {
    return s === 'published' ? 'опубликовано' : s === 'archived' ? 'в архиве' : 'черновик';
  }

  stateClass(s: string): string {
    return s === 'published' ? 'badge-success' : s === 'archived' ? 'badge-muted' : 'badge-warning';
  }
}
