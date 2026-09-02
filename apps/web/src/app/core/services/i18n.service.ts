import { Injectable, signal, computed, Pipe, PipeTransform } from '@angular/core';

export type Language = 'ru' | 'uz' | 'en';

const DICTIONARIES: Record<Language, Record<string, string>> = {
  ru: {
    'nav.tasks': 'Задачи',
    'nav.projects': 'Проекты',
    'nav.users': 'Пользователи',
    'nav.roles': 'Роли и права',
    'nav.custom_fields': 'Динамические поля',
    'nav.files': 'Файлы и хранилище',
    'nav.settings': 'Настройки системы',
    'nav.notifications': 'Уведомления',
    'nav.profile': 'Мой профиль',
    'nav.sessions': 'Мои сессии',
    'nav.tokens': 'API Токены',
    'nav.audit': 'Аудит и безопасность',

    'common.search': 'Поиск (Ctrl+K)...',
    'common.save': 'Сохранить',
    'common.cancel': 'Отмена',
    'common.create': 'Создать',
    'common.edit': 'Редактировать',
    'common.delete': 'Удалить',
    'common.block': 'Заблокировать',
    'common.unblock': 'Разблокировать',
    'common.active': 'Активен',
    'common.passive': 'Заблокирован',
    'common.load_more': 'Загрузить ещё',
    'common.no_data': 'Нет данных для отображения',
    'common.loading': 'Загрузка...',
    'common.actions': 'Действия',
    'common.status': 'Статус',
    'common.priority': 'Приоритет',
    'common.created_at': 'Создано',
    'common.logout': 'Выйти',
    'common.refresh': 'Обновить',
    'common.all': 'Все',
    'common.saved': 'Настройки успешно сохранены',

    'settings.title': 'Настройки системы и персонализация',
    'settings.subtitle': 'Иерархическая конфигурация экземпляра и пользовательские предпочтения',
    'settings.tab.general': 'Общие настройки',
    'settings.tab.security': 'Безопасность',
    'settings.tab.storage': 'Хранилище',
    'settings.tab.preferences': 'Мои предпочтения',

    'settings.company_name': 'Название компании / экземпляра',
    'settings.default_language': 'Системный язык по умолчанию',
    'settings.default_timezone': 'Часовой пояс по умолчанию',
    'settings.date_format': 'Формат отображения дат',
    'settings.min_password_len': 'Минимальная длина пароля',
    'settings.require_2fa': 'Обязательная 2FA для всех пользователей',
    'settings.session_lifetime': 'Время жизни веб-сессий (в часах)',
    'settings.default_user_quota': 'Квота пользователя по умолчанию (MB)',
    'settings.theme': 'Тема интерфейса',
    'settings.notifications_sound': 'Звуковые оповещения',

    'task.new': 'Новая задача',
    'task.title': 'Название задачи',
    'task.description': 'Описание (Markdown)',
    'task.responsible': 'Ответственный',
    'task.executors': 'Соисполнители',
    'task.parent': 'Родительская задача',
    'task.priority.low': 'Низкий',
    'task.priority.normal': 'Обычный',
    'task.priority.high': 'Высокий',
    'task.priority.urgent': 'Срочный',
    'task.comments': 'Комментарии',
    'task.add_comment': 'Написать комментарий...',

    'auth.login': 'Вход в систему',
    'auth.login_btn': 'Войти',
    'auth.username': 'Логин или Email',
    'auth.password': 'Пароль',
    'auth.otp_title': 'Двухфакторная аутентификация',
    'auth.otp_desc': 'Введите 6-значный код, отправленный в Telegram',
    'auth.otp_code': 'Код подтверждения',
    'auth.verify': 'Подтвердить'
  },
  uz: {
    'nav.tasks': 'Vazifalar',
    'nav.projects': 'Loyihalar',
    'nav.users': 'Foydalanuvchilar',
    'nav.roles': 'Rollar va huquqlar',
    'nav.custom_fields': 'Dinamik maydonlar',
    'nav.files': 'Fayllar va xotira',
    'nav.settings': 'Tizim sozlamalari',
    'nav.notifications': 'Bildirishnomalar',
    'nav.profile': 'Mening profilim',
    'nav.sessions': 'Sessiyalarim',
    'nav.tokens': 'API Tokenlar',
    'nav.audit': 'Audit va xavfsizlik',

    'common.search': 'Qidiruv (Ctrl+K)...',
    'common.save': 'Saqlash',
    'common.cancel': 'Bekor qilish',
    'common.create': 'Yaratish',
    'common.edit': 'Tahrirlash',
    'common.delete': 'O\'chirish',
    'common.block': 'Bloklash',
    'common.unblock': 'Blokdan chiqarish',
    'common.active': 'Faol',
    'common.passive': 'Bloklangan',
    'common.load_more': 'Yana yuklash',
    'common.no_data': 'Ma\'lumotlar topilmadi',
    'common.loading': 'Yuklanmoqda...',
    'common.actions': 'Amallar',
    'common.status': 'Holat',
    'common.priority': 'Muhimlik',
    'common.created_at': 'Yaratilgan',
    'common.logout': 'Chiqish',
    'common.refresh': 'Yangilash',
    'common.all': 'Barchasi',
    'common.saved': 'Sozlamalar muvaffaqiyatli saqlandi',

    'settings.title': 'Tizim sozlamalari va moslashtirish',
    'settings.subtitle': 'Tizimning ierarxik konfiguratsiyasi va shaxsiy sozlamalari',
    'settings.tab.general': 'Umumiy sozlamalar',
    'settings.tab.security': 'Xavfsizlik',
    'settings.tab.storage': 'Xotira',
    'settings.tab.preferences': 'Mening tanlovlarim',

    'settings.company_name': 'Kompaniya / Ekzemplyar nomi',
    'settings.default_language': 'Standart tizim tili',
    'settings.default_timezone': 'Standart vaqt mintaqasi',
    'settings.date_format': 'Sana ko\'rinishi formati',
    'settings.min_password_len': 'Minimal parol uzunligi',
    'settings.require_2fa': 'Barcha uchun majburiy 2FA',
    'settings.session_lifetime': 'Web sessiya muddati (soatda)',
    'settings.default_user_quota': 'Foydalanuvchining standart kvotasi (MB)',
    'settings.theme': 'Interfeys mavzusi',
    'settings.notifications_sound': 'Ovozli bildirishnomalar',

    'task.new': 'Yangi vazifa',
    'task.title': 'Vazifa nomi',
    'task.description': 'Tavsif (Markdown)',
    'task.responsible': 'Mas\'ul shaxs',
    'task.executors': 'Ijrochilar',
    'task.parent': 'Asosiy vazifa',
    'task.priority.low': 'Past',
    'task.priority.normal': 'Oddiy',
    'task.priority.high': 'Yuqori',
    'task.priority.urgent': 'Shoshilinch',
    'task.comments': 'Izohlar',
    'task.add_comment': 'Izoh yozish...',

    'auth.login': 'Tizimga kirish',
    'auth.login_btn': 'Kirish',
    'auth.username': 'Login yoki Email',
    'auth.password': 'Parol',
    'auth.otp_title': 'Ikki bosqichli autentifikatsiya',
    'auth.otp_desc': 'Telegram orqali yuborilgan 6 xonali kodni kiriting',
    'auth.otp_code': 'Tasdiqlash kodi',
    'auth.verify': 'Tasdiqlash'
  },
  en: {
    'nav.tasks': 'Tasks',
    'nav.projects': 'Projects',
    'nav.users': 'Users',
    'nav.roles': 'Roles & Permissions',
    'nav.custom_fields': 'Custom Fields',
    'nav.files': 'Files & Storage',
    'nav.settings': 'System Settings',
    'nav.notifications': 'Notifications',
    'nav.profile': 'My Profile',
    'nav.sessions': 'My Sessions',
    'nav.tokens': 'API Tokens',
    'nav.audit': 'Audit & Security',

    'common.search': 'Search (Ctrl+K)...',
    'common.save': 'Save',
    'common.cancel': 'Cancel',
    'common.create': 'Create',
    'common.edit': 'Edit',
    'common.delete': 'Delete',
    'common.block': 'Block',
    'common.unblock': 'Unblock',
    'common.active': 'Active',
    'common.passive': 'Blocked',
    'common.load_more': 'Load More',
    'common.no_data': 'No records found',
    'common.loading': 'Loading...',
    'common.actions': 'Actions',
    'common.status': 'Status',
    'common.priority': 'Priority',
    'common.created_at': 'Created At',
    'common.logout': 'Sign Out',
    'common.refresh': 'Refresh',
    'common.all': 'All',
    'common.saved': 'Settings saved successfully',

    'settings.title': 'System Settings & Personalization',
    'settings.subtitle': 'Hierarchical instance configuration and user preferences',
    'settings.tab.general': 'General Settings',
    'settings.tab.security': 'Security',
    'settings.tab.storage': 'Storage',
    'settings.tab.preferences': 'My Preferences',

    'settings.company_name': 'Company / Instance Name',
    'settings.default_language': 'Default System Language',
    'settings.default_timezone': 'Default Timezone',
    'settings.date_format': 'Date Display Format',
    'settings.min_password_len': 'Minimum Password Length',
    'settings.require_2fa': 'Require 2FA for all users',
    'settings.session_lifetime': 'Web Session Lifetime (hours)',
    'settings.default_user_quota': 'Default User Storage Quota (MB)',
    'settings.theme': 'Interface Theme',
    'settings.notifications_sound': 'Sound Notifications',

    'task.new': 'New Task',
    'task.title': 'Task Title',
    'task.description': 'Description (Markdown)',
    'task.responsible': 'Responsible',
    'task.executors': 'Executors',
    'task.parent': 'Parent Task',
    'task.priority.low': 'Low',
    'task.priority.normal': 'Normal',
    'task.priority.high': 'High',
    'task.priority.urgent': 'Urgent',
    'task.comments': 'Comments',
    'task.add_comment': 'Write a comment...',

    'auth.login': 'Sign In',
    'auth.login_btn': 'Sign In',
    'auth.username': 'Login or Email',
    'auth.password': 'Password',
    'auth.otp_title': 'Two-Factor Authentication',
    'auth.otp_desc': 'Enter 6-digit verification code sent via Telegram',
    'auth.otp_code': 'Verification Code',
    'auth.verify': 'Verify'
  }
};


export interface LanguageInfo {
  code: string;
  name: string;
  isCustom?: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class I18nService {
  private readonly customDictionaries: Record<string, Record<string, string>> = {};
  readonly languages = signal<LanguageInfo[]>([
    { code: 'ru', name: 'Русский' },
    { code: 'uz', name: 'Oʻzbekcha' },
    { code: 'en', name: 'English' },
    { code: 'kk', name: 'Қазақша', isCustom: true },
    { code: 'ky', name: 'Кыргызча', isCustom: true },
    { code: 'tg', name: 'Тоҷикӣ', isCustom: true },
    { code: 'de', name: 'Deutsch', isCustom: true },
    { code: 'tr', name: 'Türkçe', isCustom: true }
  ]);

  readonly currentLang = signal<string>('ru');

  constructor() {
    this.loadCustomLanguagesFromStorage();
    const saved = localStorage.getItem('dwh_lang');
    if (saved) {
      this.currentLang.set(saved);
    }
  }

  setLanguage(lang: string) {
    this.currentLang.set(lang);
    localStorage.setItem('dwh_lang', lang);
  }

  registerLanguage(code: string, name: string, dictionary: Record<string, string>) {
    const cleanCode = code.toLowerCase().trim();
    this.customDictionaries[cleanCode] = dictionary;

    const current = this.languages();
    if (!current.some(l => l.code === cleanCode)) {
      this.languages.set([...current, { code: cleanCode, name, isCustom: true }]);
    }

    this.saveCustomLanguagesToStorage();
  }

  getDictionary(lang: string): Record<string, string> {
    const safe = lang.toLowerCase().trim();
    if (this.customDictionaries[safe]) {
      return { ...DICTIONARIES['ru'], ...this.customDictionaries[safe] };
    }
    if (DICTIONARIES[safe as Language]) {
      return DICTIONARIES[safe as Language];
    }
    return DICTIONARIES['ru'];
  }

  exportDictionary(lang: string): string {
    return JSON.stringify(this.getDictionary(lang), null, 2);
  }

  importDictionary(code: string, name: string, jsonContent: string): boolean {
    try {
      const parsed = JSON.parse(jsonContent);
      if (typeof parsed === 'object' && parsed !== null) {
        this.registerLanguage(code, name, parsed);
        return true;
      }
    } catch (e) {
      console.error('Failed to import dictionary:', e);
    }
    return false;
  }

  translate(key: string): string {
    const lang = this.currentLang();
    if (this.customDictionaries[lang]?.[key]) {
      return this.customDictionaries[lang][key];
    }
    const standard = DICTIONARIES[lang as Language];
    if (standard?.[key]) {
      return standard[key];
    }
    return DICTIONARIES['ru']?.[key] || key;
  }

  private loadCustomLanguagesFromStorage() {
    try {
      const raw = localStorage.getItem('dwh_custom_languages');
      if (raw) {
        const stored = JSON.parse(raw);
        for (const [code, entry] of Object.entries<any>(stored)) {
          this.customDictionaries[code] = entry.dict || {};
          if (!this.languages().some(l => l.code === code)) {
            this.languages.set([...this.languages(), { code, name: entry.name, isCustom: true }]);
          }
        }
      }
    } catch (e) {
      console.error('Failed to load custom languages:', e);
    }
  }

  private saveCustomLanguagesToStorage() {
    try {
      const payload: Record<string, any> = {};
      for (const lang of this.languages().filter(l => l.isCustom)) {
        payload[lang.code] = {
          name: lang.name,
          dict: this.customDictionaries[lang.code] || {}
        };
      }
      localStorage.setItem('dwh_custom_languages', JSON.stringify(payload));
    } catch (e) {
      console.error('Failed to save custom languages:', e);
    }
  }
}

@Pipe({
  name: 't',
  standalone: true,
  pure: false
})
export class TranslatePipe implements PipeTransform {
  constructor(private i18n: I18nService) {}

  transform(key: string): string {
    return this.i18n.translate(key);
  }
}
