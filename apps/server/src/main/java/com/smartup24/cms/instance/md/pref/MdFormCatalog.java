package com.smartup24.cms.instance.md.pref;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Human-readable names of forms and actions (FR-PERM-1).
 *
 * The split of responsibility is deliberate: the **existence** of a "form x action" pair
 * is defined by {@code @RequiresPermission} annotations in controllers; they are the
 * single source of truth because they are what actually guards the endpoints.
 * Only the names for the permission matrix screen live here.
 *
 * Forms of entities declared through {@code EntityDefinition} name themselves
 * ({@code EntityRights}) and are not repeated here.
 *
 * A pair declared by an annotation but forgotten here gets its own code as its name
 * and does not break anything; the build is then failed by the
 * {@code everyDeclaredPermissionHasHumanName} test. The reverse case, a name without
 * an annotation, means a dead catalog entry and is marked obsolete.
 */
public final class MdFormCatalog {

    private MdFormCatalog() {}

    /** A form: the owning module, its name and the names of its actions. */
    public record FormMeta(String module, String name, Map<String, String> actionNames) {}

    private static final Map<String, FormMeta> FORMS = buildForms();

    public static Optional<FormMeta> find(String formCode) {
        return Optional.ofNullable(FORMS.get(formCode));
    }

    /** The form's module; for an unknown form, the code prefix, so grouping in the UI does not fall apart. */
    public static String moduleOf(String formCode) {
        var meta = FORMS.get(formCode);
        if (meta != null) {
            return meta.module();
        }
        int dot = formCode.indexOf('.');
        return dot > 0 ? formCode.substring(0, dot) : formCode;
    }

    public static String formNameOf(String formCode) {
        var meta = FORMS.get(formCode);
        return meta != null ? meta.name() : formCode;
    }

    public static String actionNameOf(String formCode, String action) {
        var meta = FORMS.get(formCode);
        if (meta == null) {
            return action;
        }
        return meta.actionNames().getOrDefault(action, action);
    }

    /** Whether the pair has a name; used by the catalog completeness test. */
    public static boolean hasHumanName(String formCode, String action) {
        var meta = FORMS.get(formCode);
        return meta != null && meta.actionNames().containsKey(action);
    }

    private static Map<String, FormMeta> buildForms() {
        Map<String, FormMeta> forms = new LinkedHashMap<>();
        putIdentityForms(forms);
        putAdministrationForms(forms);
        putUploadAndTaskForms(forms);
        putCommunicationForms(forms);
        putPlatformForms(forms);
        return Map.copyOf(forms);
    }

    /** Profile, users, roles and assignments: who the user is and what they may do. */
    private static void putIdentityForms(Map<String, FormMeta> forms) {
        forms.put(
                MdPref.FORM_PROFILE,
                new FormMeta(
                        "md",
                        "Мой профиль",
                        ordered(
                                "view", "Просмотр профиля",
                                "update", "Изменение данных профиля",
                                "manage_channels", "Управление каналами связи",
                                "manage_tokens", "Управление API-токенами")));

        forms.put(
                MdPref.FORM_USERS,
                new FormMeta(
                        "md",
                        "Пользователи",
                        ordered(
                                "view", "Просмотр списка",
                                "create", "Создание пользователя",
                                "update", "Редактирование",
                                "block", "Блокировка",
                                "unblock", "Разблокировка",
                                "delete", "Удаление (анонимизация)")));

        forms.put(
                MdPref.FORM_ROLES,
                new FormMeta(
                        "md",
                        "Роли и права",
                        ordered(
                                "view", "Просмотр ролей",
                                "create", "Создание роли",
                                "update", "Редактирование",
                                "delete", "Удаление",
                                "grant", "Настройка матрицы прав")));

        forms.put(
                MdPref.FORM_ASSIGNMENTS,
                new FormMeta(
                        "md",
                        "Назначение прав",
                        ordered(
                                "view", "Просмотр назначений",
                                "assign", "Назначение ролей и прав")));
    }

    private static void putAdministrationForms(Map<String, FormMeta> forms) {
        forms.put(
                MdPref.FORM_CUSTOM_FIELDS,
                new FormMeta(
                        "md",
                        "Динамические поля",
                        ordered(
                                "view", "Просмотр полей",
                                "create", "Создание поля",
                                "update", "Редактирование",
                                "delete", "Удаление")));

        forms.put(
                MdPref.FORM_ORG_UNITS,
                new FormMeta(
                        "md",
                        "Оргструктура",
                        ordered(
                                "view", "Просмотр оргструктуры",
                                "create", "Создание узла",
                                "update", "Редактирование узла",
                                "delete", "Удаление узла",
                                "assign", "Назначение сотрудников и правил видимости")));

        forms.put(
                MdPref.FORM_SETTINGS,
                new FormMeta(
                        "md",
                        "Настройки платформы",
                        ordered(
                                "view", "Просмотр настроек",
                                "update", "Изменение настроек")));

        forms.put("audit.log", new FormMeta("audit", "Аудит и security-журнал", ordered("view", "Просмотр журналов")));
    }

    private static void putUploadAndTaskForms(Map<String, FormMeta> forms) {
        forms.put(
                "upl.sources",
                new FormMeta(
                        "upl",
                        "Manbalar va formatlar",
                        ordered(
                                "view", "Ko'rish",
                                "create", "Yaratish",
                                "edit", "Tahrirlash",
                                "publish", "E'lon qilish")));

        forms.put(
                "upl.packages",
                new FormMeta(
                        "upl",
                        "Загрузки файлов",
                        ordered(
                                "view", "Просмотр",
                                "upload", "Загрузка файла",
                                "apply", "Применение")));

        forms.put(
                "tasks.projects",
                new FormMeta(
                        "ms.task",
                        "Проекты",
                        ordered(
                                "view", "Просмотр проектов",
                                "create", "Создание проекта",
                                "update", "Редактирование проекта")));

        forms.put(
                "tasks.items",
                new FormMeta(
                        "ms.task",
                        "Задачи",
                        ordered(
                                "view", "Просмотр задач",
                                "create", "Создание задачи",
                                "update", "Редактирование задачи")));

        forms.put(
                "tasks.comments",
                new FormMeta(
                        "ms.task",
                        "Комментарии к задачам",
                        ordered(
                                "view", "Просмотр комментариев",
                                "create", "Создание комментария")));
    }

    /** Notifications, announcements, files and search. */
    private static void putCommunicationForms(Map<String, FormMeta> forms) {
        forms.put(
                "notify.inbox", new FormMeta("ms.notify", "Входящие оповещения", ordered("view", "Просмотр входящих")));

        forms.put(
                "platform.announcements",
                new FormMeta(
                        "ms.notify",
                        "Объявления",
                        ordered(
                                "view", "Просмотр объявлений",
                                "create", "Создание объявления",
                                "update", "Редактирование объявления",
                                "publish", "Публикация объявления",
                                "archive", "Архивация объявления")));

        forms.put(
                "platform.files",
                new FormMeta(
                        "mf",
                        "Файлы",
                        ordered(
                                "view", "Просмотр и скачивание",
                                "upload", "Загрузка файлов",
                                "delete", "Удаление файлов")));

        forms.put("platform.search", new FormMeta("search", "Поиск", ordered("view", "Полнотекстовый поиск")));
    }

    /** Webhooks, analytics, modules and navigation. */
    private static void putPlatformForms(Map<String, FormMeta> forms) {
        forms.put(
                "platform.webhooks",
                new FormMeta(
                        "kwh",
                        "Исходящие вебхуки",
                        ordered(
                                "view", "Просмотр подписок",
                                "manage", "Управление подписками")));

        forms.put(
                "analytics.dashboard",
                new FormMeta(
                        "analytics",
                        "Аналитика и дашборды",
                        ordered(
                                "view", "Просмотр аналитики",
                                "manage", "Управление дашбордами")));

        forms.put(
                "platform.modules",
                new FormMeta(
                        "md",
                        "Модули системы",
                        ordered(
                                "view", "Просмотр установленных модулей",
                                "manage", "Управление активностью модулей")));

        forms.put(
                MdPref.FORM_NAVIGATION,
                new FormMeta(
                        "md",
                        "Навигация и меню",
                        ordered(
                                "view", "Просмотр меню и отчетов",
                                "manage", "Управление пунктами меню и отчетами")));
    }

    private static Map<String, String> ordered(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return Map.copyOf(map);
    }
}
