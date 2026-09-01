import { Component, OnInit, computed, inject, signal, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  Client,
  CpApiService,
  InstanceEnrollment,
  InstanceRegistrationRequest
} from '../core/cp-api.service';
import { dt, errorText } from '../core/format';
import { UiPaginationComponent } from '../shared/ui-pagination.component';

type ClientSortCol = 'id' | 'name' | 'code' | 'resourceProfile' | 'createdAt';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'cp-clients',
  standalone: true,
  imports: [CommonModule, FormsModule, UiPaginationComponent],
  template: `
    <!-- Page Header -->
    <div class="view-header">
      <div class="header-left">
        <h1 class="view-title">Клиенты и Организации</h1>
        <span class="count-badge">{{ clients().length }}</span>
      </div>

      <div class="header-right" *ngIf="canManage()">
        <button type="button" class="btn btn-secondary" (click)="openRegisterModal()">
          <span class="material-symbols-outlined" aria-hidden="true">dns</span>
          <span>Зарегистрировать экземпляр</span>
        </button>
        <button type="button" class="btn btn-primary" (click)="openCreateClientModal()">
          <span class="material-symbols-outlined" aria-hidden="true">add</span>
          <span>Новый клиент</span>
        </button>
      </div>
    </div>

    <!-- Error Alert -->
    <div *ngIf="error()" class="alert alert-error" role="alert">
      <span class="material-symbols-outlined">error</span>
      <span>{{ error() }}</span>
    </div>

    <!-- Issued Token Banner -->
    <div *ngIf="issuedEnrollment() as enrollment" class="alert alert-success" role="status" style="display: flex; flex-direction: column; align-items: flex-start; gap: 8px;">
      <div style="display: flex; align-items: center; justify-content: space-between; width: 100%;">
        <div style="font-weight: 700; display: flex; align-items: center; gap: 6px;">
          <span class="material-symbols-outlined">key</span>
          <span>Одноразовый enrollment-токен создан</span>
        </div>
        <button type="button" class="btn btn-sm btn-secondary" (click)="dismissIssuedEnrollment()">Закрыть</button>
      </div>
      <div style="font-size: 12px; color: var(--text-main);">
        Передайте токен установщику по защищённому каналу. Он отображается один раз.
        <span style="display: block; margin-top: 4px;">Действует до <strong>{{ dt(enrollment.expiresAt) }}</strong>.</span>
      </div>
      <div style="display: flex; align-items: center; gap: 8px; width: 100%; margin-top: 4px;">
        <code class="mono" style="background: var(--bg-surface); padding: 8px 12px; border-radius: var(--radius-sm); border: 1px solid var(--border-color); flex: 1; word-break: break-all; font-weight: 600;">{{ enrollment.enrollmentToken }}</code>
        <button type="button" class="btn btn-secondary btn-sm" (click)="copyToken(enrollment.enrollmentToken)">
          <span class="material-symbols-outlined" style="font-size: 16px;">{{ copied() ? 'check' : 'content_copy' }}</span>
          <span>{{ copied() ? 'Скопировано' : 'Копировать' }}</span>
        </button>
      </div>
    </div>

    <!-- Filter & Search Toolbar (Linear Style matching Portal) -->
    <div class="toolbar">
      <div class="search-field">
        <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
        <label class="sr-only" for="client-search">Поиск по названию или коду</label>
        <input
          id="client-search"
          name="clientSearch"
          type="text"
          class="search-input"
          placeholder="Поиск по названию или коду..."
          [(ngModel)]="searchQuery"
          (ngModelChange)="currentPage = 1"
        />
        <button
          *ngIf="searchQuery"
          type="button"
          class="btn-icon"
          style="position: absolute; right: 6px; padding: 2px;"
          (click)="searchQuery = ''; currentPage = 1"
          title="Очистить"
        >
          <span class="material-symbols-outlined" style="font-size: 16px;">close</span>
        </button>
      </div>

      <div class="status-tabs" role="group" aria-label="Фильтр по профилю">
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedProfile === 'ALL'"
          (click)="selectedProfile = 'ALL'; currentPage = 1"
        >
          Все ({{ clients().length }})
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedProfile === 'S'"
          (click)="selectedProfile = 'S'; currentPage = 1"
        >
          <span class="status-tab-dot" style="background-color: var(--primary);" aria-hidden="true"></span>
          Профиль S
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedProfile === 'M'"
          (click)="selectedProfile = 'M'; currentPage = 1"
        >
          <span class="status-tab-dot" style="background-color: var(--warning);" aria-hidden="true"></span>
          Профиль M
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedProfile === 'L'"
          (click)="selectedProfile = 'L'; currentPage = 1"
        >
          <span class="status-tab-dot" style="background-color: var(--danger);" aria-hidden="true"></span>
          Профиль L
        </button>
      </div>
    </div>

    <!-- Data Table Card -->
    <div class="table-card">
      <div class="table-scroll" role="region" aria-label="Таблица клиентов" tabindex="0">
        <table aria-label="Список клиентов">
          <thead>
            <tr>
              <th class="sortable-th" (click)="setSort('id')" style="width: 60px;">
                <span>ID</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'id'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('name')">
                <span>Организация</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'name'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('code')">
                <span>Код клиента</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'code'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('resourceProfile')">
                <span>Профиль ресурсов</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'resourceProfile'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('createdAt')">
                <span>Дата создания</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'createdAt'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th style="text-align: right; width: 70px;">Действия</th>
            </tr>
          </thead>
          <tbody>
            <tr
              *ngFor="let c of pagedClients(); trackBy: trackById"
              class="clickable-row"
              (click)="openDrawer(c)"
            >
              <td class="tabular-nums mono" style="font-weight: 600; color: var(--text-muted);">#{{ c.id }}</td>
              <td>
                <div style="font-weight: 600; color: var(--text-main);">{{ c.name }}</div>
              </td>
              <td>
                <span class="mono badge badge-neutral">{{ c.code }}</span>
              </td>
              <td>
                <span class="badge" [ngClass]="{
                  'badge-primary': c.resourceProfile === 'S',
                  'badge-warning': c.resourceProfile === 'M',
                  'badge-danger': c.resourceProfile === 'L'
                }">
                  Профиль {{ c.resourceProfile }}
                </span>
              </td>
              <td style="color: var(--text-muted);">
                {{ dt(c.createdAt) }}
              </td>
              <td style="text-align: right;" (click)="$event.stopPropagation()">
                <button
                  type="button"
                  class="btn-icon"
                  (click)="openDrawer(c)"
                  title="Просмотреть детали"
                >
                  <span class="material-symbols-outlined" style="font-size: 19px;">visibility</span>
                </button>
              </td>
            </tr>
            <tr *ngIf="sortedAndFilteredClients().length === 0 && !busy()">
              <td colspan="6" class="empty">
                <div style="display: flex; flex-direction: column; align-items: center; gap: 8px;">
                  <span class="material-symbols-outlined" style="font-size: 36px; color: var(--text-light);">corporate_fare</span>
                  <span>Клиентов по заданным параметрам не найдено.</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Bottom Pagination Bar -->
      <ui-pagination
        [totalItems]="sortedAndFilteredClients().length"
        [pageSize]="pageSize"
        [currentPage]="currentPage"
        (pageChange)="currentPage = $event"
        (pageSizeChange)="pageSize = $event; currentPage = 1"
      ></ui-pagination>
    </div>

    <!-- MODAL: Новый клиент -->
    <div class="modal-overlay" *ngIf="showCreateModal">
      <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="create-client-title">
        <div class="modal-header">
          <h2 id="create-client-title" class="modal-title">Создание новой организации</h2>
          <button type="button" class="btn-icon" (click)="showCreateModal = false" aria-label="Закрыть окно">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>
        <form (ngSubmit)="createClient()">
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label" for="new-client-name">Название компании *</label>
              <input
                id="new-client-name"
                name="newClientName"
                type="text"
                class="form-input"
                [(ngModel)]="name"
                placeholder="ООО «Акме Корпорейшн»"
                required
              />
            </div>
            <div class="form-group">
              <label class="form-label" for="new-client-code">Системный код (латиница) *</label>
              <input
                id="new-client-code"
                name="newClientCode"
                type="text"
                class="form-input mono"
                [(ngModel)]="code"
                placeholder="acme_corp"
                required
              />
            </div>
            <div class="form-group">
              <label class="form-label" for="new-client-profile">Профиль ресурсов *</label>
              <select id="new-client-profile" name="newClientProfile" class="form-select" [(ngModel)]="profile">
                <option value="S">S — Малый бизнес (до 50 пользователей)</option>
                <option value="M">M — Средний бизнес (до 500 пользователей)</option>
                <option value="L">L — Enterprise (High-Load кластер)</option>
              </select>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-secondary" (click)="showCreateModal = false">Отмена</button>
            <button type="submit" class="btn btn-primary" [disabled]="busy() || !name || !code">
              {{ busy() ? 'Создаем...' : 'Создать организацию' }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- MODAL: Регистрация экземпляра -->
    <div class="modal-overlay" *ngIf="showRegisterModal">
      <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="register-instance-title">
        <div class="modal-header">
          <h2 id="register-instance-title" class="modal-title">Регистрация экземпляра клиента</h2>
          <button type="button" class="btn-icon" (click)="closeRegisterModal()" aria-label="Закрыть окно">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>
        <form (ngSubmit)="registerInstance()">
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label" for="reg-client">Клиент (организация) *</label>
              <select id="reg-client" name="regClient" class="form-select" [(ngModel)]="instClient" required>
                <option value="">— Выберите организацию —</option>
                <option *ngFor="let c of clients()" [value]="c.code">
                  {{ c.name }} ({{ c.code }})
                </option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-env">Контур окружения *</label>
              <select id="reg-env" name="regEnv" class="form-select" [(ngModel)]="instEnv" required>
                <option value="production">production — Промышленный контур</option>
                <option value="staging">staging — Тестовый предпрод</option>
                <option value="dev">dev — Разработка</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-mode">Режим размещения *</label>
              <select
                id="reg-mode"
                name="regMode"
                class="form-select"
                [(ngModel)]="instDeploymentMode"
                (ngModelChange)="applyDeploymentDefaults()"
                required
              >
                <option value="MANAGED_CLOUD">Наше облако — управляется платформой</option>
                <option value="CUSTOMER_HOSTED">Инфраструктура клиента</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-jurisdiction">Юрисдикция *</label>
              <input id="reg-jurisdiction" name="regJurisdiction" class="form-input"
                     [(ngModel)]="instJurisdiction"
                     [readOnly]="instDeploymentMode === 'MANAGED_CLOUD'" maxlength="64" required />
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-cloud">Облачный провайдер *</label>
              <input id="reg-cloud" name="regCloud" class="form-input mono"
                     [(ngModel)]="instCloudProvider"
                     [readOnly]="instDeploymentMode === 'MANAGED_CLOUD'" maxlength="64" required />
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-storage">Объектное хранилище *</label>
              <input id="reg-storage" name="regStorage" class="form-input mono"
                     [(ngModel)]="instStorageProvider"
                     [readOnly]="instDeploymentMode === 'MANAGED_CLOUD'" maxlength="64" required />
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-edge">Edge и защита</label>
              <input id="reg-edge" name="regEdge" class="form-input mono"
                     [(ngModel)]="instEdgeProvider"
                     [readOnly]="instDeploymentMode === 'MANAGED_CLOUD'" maxlength="64" />
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-support">Уровень поддержки *</label>
              <input id="reg-support" name="regSupport" class="form-input mono"
                     [(ngModel)]="instSupportTier" readOnly required />
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-url">URL экземпляра *</label>
              <input
                id="reg-url"
                name="regUrl"
                type="url"
                class="form-input mono"
                [(ngModel)]="instUrl"
                placeholder="https://acme.dwh.internal"
                required
              />
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-secondary" (click)="closeRegisterModal()">Отмена</button>
            <button type="submit" class="btn btn-primary" [disabled]="busy() || !canRegisterInstance()">
              {{ busy() ? 'Регистрируем...' : 'Создать enrollment' }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- DRAWER: Детали клиента -->
    <div class="drawer-backdrop" *ngIf="selectedClient" (click)="closeDrawer()"></div>
    <aside class="drawer-panel" *ngIf="selectedClient" role="dialog" aria-modal="true" aria-label="Карточка организации">
      <div class="drawer-header">
        <div>
          <div style="display: flex; align-items: center; gap: 8px;">
            <h2 class="drawer-title">{{ selectedClient.name }}</h2>
            <span class="badge badge-neutral mono">{{ selectedClient.code }}</span>
          </div>
          <p class="drawer-subtitle">ID клиента: #{{ selectedClient.id }} · Зарегистрирован {{ dt(selectedClient.createdAt) }}</p>
        </div>
        <button type="button" class="btn-icon" (click)="closeDrawer()" aria-label="Закрыть шторку">
          <span class="material-symbols-outlined">close</span>
        </button>
      </div>

      <div class="drawer-body">
        <div class="drawer-section">
          <h3 class="section-title">Параметры профиля</h3>
          <div class="drawer-grid">
            <div class="drawer-info-block">
              <span class="info-label">Профиль масштабирования</span>
              <span class="info-value">Профиль {{ selectedClient.resourceProfile }}</span>
            </div>
            <div class="drawer-info-block">
              <span class="info-label">Системный код</span>
              <span class="info-value mono">{{ selectedClient.code }}</span>
            </div>
          </div>
        </div>

        <div class="drawer-section">
          <h3 class="section-title">Быстрые действия</h3>
          <div style="display: flex; gap: 10px; margin-top: 8px;">
            <button
              type="button"
              class="btn btn-primary"
              (click)="registerForThisClient(selectedClient)"
            >
              <span class="material-symbols-outlined">dns</span>
              <span>Добавить экземпляр</span>
            </button>
          </div>
        </div>
      </div>
    </aside>
  `,
  styles: [`
    .sortable-th {
      cursor: pointer;
      user-select: none;
      transition: background-color 0.1s ease;
    }
    .sortable-th:hover {
      background-color: var(--border-color);
      color: var(--text-main);
    }
    .sort-icon {
      font-size: 14px;
      margin-left: 4px;
      vertical-align: text-bottom;
    }

    .clickable-row {
      cursor: pointer;
    }

    /* Slide-Over Drawer */
    .drawer-backdrop {
      position: fixed;
      inset: 0;
      background-color: rgba(15, 23, 42, 0.5);
      backdrop-filter: blur(2px);
      z-index: 1200;
      animation: fadeIn 0.2s ease;
    }

    .drawer-panel {
      position: fixed;
      top: 0;
      right: 0;
      bottom: 0;
      width: 480px;
      max-width: 90vw;
      background-color: var(--bg-surface);
      border-left: 1px solid var(--border-color);
      box-shadow: var(--shadow-overlay);
      z-index: 1300;
      display: flex;
      flex-direction: column;
      animation: slideIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
    }

    @keyframes fadeIn {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    @keyframes slideIn {
      from { transform: translateX(100%); }
      to { transform: translateX(0); }
    }

    .drawer-header {
      padding: 18px 22px;
      border-bottom: 1px solid var(--border-color);
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
    }
    .drawer-title {
      font-size: 17px;
      font-weight: 700;
      color: var(--text-main);
    }
    .drawer-subtitle {
      font-size: 12px;
      color: var(--text-muted);
      margin-top: 3px;
    }

    .drawer-body {
      flex: 1;
      overflow-y: auto;
      padding: 22px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .drawer-section {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding-bottom: 16px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .drawer-section:last-child {
      border-bottom: none;
      padding-bottom: 0;
    }

    .section-title {
      font-size: 12px;
      text-transform: uppercase;
      font-weight: 700;
      letter-spacing: 0.5px;
      color: var(--text-muted);
    }

    .drawer-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }

    .drawer-info-block {
      display: flex;
      flex-direction: column;
      gap: 3px;
    }
    .info-label {
      font-size: 11px;
      color: var(--text-light);
      font-weight: 500;
    }
    .info-value {
      font-size: 13px;
      color: var(--text-main);
      font-weight: 600;
    }
  `]
})
export class ClientsComponent implements OnInit {
  private api = inject(CpApiService);

  clients = signal<Client[]>([]);
  busy = signal(false);
  error = signal('');
  issuedEnrollment = signal<InstanceEnrollment | null>(null);
  copied = signal(false);

  searchQuery = '';
  selectedProfile = 'ALL';

  sortCol: ClientSortCol = 'id';
  sortDir: SortDir = 'asc';

  currentPage = 1;
  pageSize = 10;

  selectedClient: Client | null = null;

  showCreateModal = false;
  showRegisterModal = false;

  code = '';
  name = '';
  profile = 'S';

  instClient = '';
  instEnv: InstanceRegistrationRequest['environment'] = 'production';
  instUrl = '';
  instDeploymentMode: InstanceRegistrationRequest['deploymentMode'] = 'MANAGED_CLOUD';
  instJurisdiction = 'EU';
  instCloudProvider = 'HETZNER';
  instStorageProvider = 'CLOUDFLARE_R2';
  instEdgeProvider = 'CLOUDFLARE';
  instSupportTier: InstanceRegistrationRequest['supportTier'] = 'MANAGED_995';

  dt = dt;

  canManage = computed(() => {
    const roles = this.api.user()?.roles ?? [];
    return roles.includes('cp-admin') || roles.includes('cp-engineer');
  });

  @HostListener('window:keydown.escape')
  handleEscape(): void {
    if (this.selectedClient) this.closeDrawer();
    if (this.showCreateModal) this.showCreateModal = false;
    if (this.showRegisterModal) this.closeRegisterModal();
  }

  sortedAndFilteredClients(): Client[] {
    const q = this.searchQuery.trim().toLowerCase();
    const filtered = this.clients().filter(c => {
      const matchesText = !q
        || (c.name && c.name.toLowerCase().includes(q))
        || (c.code && c.code.toLowerCase().includes(q));

      if (!matchesText) return false;
      if (this.selectedProfile !== 'ALL' && c.resourceProfile !== this.selectedProfile) return false;

      return true;
    });

    return filtered.sort((a, b) => {
      let valA: string | number = '';
      let valB: string | number = '';

      switch (this.sortCol) {
        case 'id': valA = a.id; valB = b.id; break;
        case 'name': valA = a.name; valB = b.name; break;
        case 'code': valA = a.code; valB = b.code; break;
        case 'resourceProfile': valA = a.resourceProfile; valB = b.resourceProfile; break;
        case 'createdAt': valA = a.createdAt; valB = b.createdAt; break;
      }

      if (valA === valB) return 0;
      const comp = valA > valB ? 1 : -1;
      return this.sortDir === 'asc' ? comp : -comp;
    });
  }

  pagedClients(): Client[] {
    const list = this.sortedAndFilteredClients();
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }

  ngOnInit(): void {
    void this.load();
  }

  trackById(index: number, item: Client): number {
    return item.id;
  }

  setSort(col: ClientSortCol): void {
    if (this.sortCol === col) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortCol = col;
      this.sortDir = 'asc';
    }
  }

  openDrawer(c: Client): void {
    this.selectedClient = c;
    document.body.classList.add('modal-open');
  }

  closeDrawer(): void {
    this.selectedClient = null;
    document.body.classList.remove('modal-open');
  }

  registerForThisClient(c: Client): void {
    this.closeDrawer();
    this.instClient = c.code;
    this.instEnv = 'production';
    this.instUrl = '';
    this.instDeploymentMode = 'MANAGED_CLOUD';
    this.applyDeploymentDefaults();
    this.dismissIssuedEnrollment();
    this.showRegisterModal = true;
  }

  openCreateClientModal(): void {
    this.code = '';
    this.name = '';
    this.profile = 'S';
    this.showCreateModal = true;
  }

  openRegisterModal(): void {
    this.instClient = '';
    this.instEnv = 'production';
    this.instUrl = '';
    this.instDeploymentMode = 'MANAGED_CLOUD';
    this.applyDeploymentDefaults();
    this.dismissIssuedEnrollment();
    this.showRegisterModal = true;
  }

  closeRegisterModal(): void {
    this.showRegisterModal = false;
    this.instUrl = '';
  }

  applyDeploymentDefaults(): void {
    if (this.instDeploymentMode === 'MANAGED_CLOUD') {
      this.instJurisdiction = 'EU';
      this.instCloudProvider = 'HETZNER';
      this.instStorageProvider = 'CLOUDFLARE_R2';
      this.instEdgeProvider = 'CLOUDFLARE';
      this.instSupportTier = 'MANAGED_995';
      return;
    }
    this.instJurisdiction = '';
    this.instCloudProvider = '';
    this.instStorageProvider = '';
    this.instEdgeProvider = '';
    this.instSupportTier = 'CUSTOMER_HOSTED_SUPPORT';
  }

  canRegisterInstance(): boolean {
    return Boolean(
      this.instClient
      && this.instUrl
      && this.instJurisdiction.trim()
      && this.instCloudProvider.trim()
      && this.instStorageProvider.trim()
      && this.instSupportTier
    );
  }

  async load(): Promise<void> {
    this.busy.set(true);
    try {
      this.clients.set(await this.api.clients());
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось загрузить список клиентов'));
    } finally {
      this.busy.set(false);
    }
  }

  async createClient(): Promise<void> {
    if (!this.code || !this.name) return;
    this.busy.set(true);
    try {
      await this.api.createClient(this.code.trim(), this.name.trim(), this.profile);
      this.showCreateModal = false;
      this.code = '';
      this.name = '';
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось создать клиента'));
    } finally {
      this.busy.set(false);
    }
  }

  async registerInstance(): Promise<void> {
    if (!this.canRegisterInstance()) return;
    this.busy.set(true);
    try {
      const request: InstanceRegistrationRequest = {
        clientCode: this.instClient,
        environment: this.instEnv,
        url: this.instUrl.trim(),
        deploymentMode: this.instDeploymentMode,
        jurisdiction: this.instJurisdiction.trim(),
        cloudProvider: this.instCloudProvider.trim(),
        storageProvider: this.instStorageProvider.trim(),
        edgeProvider: this.instEdgeProvider.trim() || null,
        supportTier: this.instSupportTier
      };
      const resp = await this.api.registerInstance(request);
      this.issuedEnrollment.set(resp);
      this.closeRegisterModal();
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось зарегистрировать экземпляр'));
    } finally {
      this.busy.set(false);
    }
  }

  async copyToken(token: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(token);
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    } catch {
      // ignore
    }
  }

  dismissIssuedEnrollment(): void {
    this.issuedEnrollment.set(null);
    this.copied.set(false);
  }
}
