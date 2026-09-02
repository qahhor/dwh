package com.greenwhite.dwh.instance.md.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/i18n")
public class MdI18nController {

    private static final Map<String, Map<String, String>> DICTIONARIES = Map.of(
            "ru", Map.ofEntries(
                    Map.entry("nav.tasks", "Задачи"),
                    Map.entry("nav.projects", "Проекты"),
                    Map.entry("nav.users", "Пользователи"),
                    Map.entry("nav.roles", "Роли и права"),
                    Map.entry("nav.custom_fields", "Динамические поля"),
                    Map.entry("nav.files", "Файлы"),
                    Map.entry("nav.audit", "Аудит и безопасность"),
                    Map.entry("nav.settings", "Настройки системы"),
                    Map.entry("nav.notifications", "Уведомления"),
                    Map.entry("nav.profile", "Мой профиль"),
                    Map.entry("common.save", "Сохранить"),
                    Map.entry("common.cancel", "Отмена"),
                    Map.entry("common.search", "Поиск..."),
                    Map.entry("common.create", "Создать"),
                    Map.entry("common.edit", "Редактировать"),
                    Map.entry("common.delete", "Удалить")
            ),
            "uz", Map.ofEntries(
                    Map.entry("nav.tasks", "Vazifalar"),
                    Map.entry("nav.projects", "Loyihalar"),
                    Map.entry("nav.users", "Foydalanuvchilar"),
                    Map.entry("nav.roles", "Rollar va huquqlar"),
                    Map.entry("nav.custom_fields", "Dinamik maydonlar"),
                    Map.entry("nav.files", "Fayllar"),
                    Map.entry("nav.audit", "Audit va xavfsizlik"),
                    Map.entry("nav.settings", "Tizim sozlamalari"),
                    Map.entry("nav.notifications", "Bildirishnomalar"),
                    Map.entry("nav.profile", "Mening profilim"),
                    Map.entry("common.save", "Saqlash"),
                    Map.entry("common.cancel", "Bekor qilish"),
                    Map.entry("common.search", "Qidiruv..."),
                    Map.entry("common.create", "Yaratish"),
                    Map.entry("common.edit", "Tahrirlash"),
                    Map.entry("common.delete", "O'chirish")
            ),
            "en", Map.ofEntries(
                    Map.entry("nav.tasks", "Tasks"),
                    Map.entry("nav.projects", "Projects"),
                    Map.entry("nav.users", "Users"),
                    Map.entry("nav.roles", "Roles & Permissions"),
                    Map.entry("nav.custom_fields", "Custom Fields"),
                    Map.entry("nav.files", "Files"),
                    Map.entry("nav.audit", "Audit & Security"),
                    Map.entry("nav.settings", "System Settings"),
                    Map.entry("nav.notifications", "Notifications"),
                    Map.entry("nav.profile", "My Profile"),
                    Map.entry("common.save", "Save"),
                    Map.entry("common.cancel", "Cancel"),
                    Map.entry("common.search", "Search..."),
                    Map.entry("common.create", "Create"),
                    Map.entry("common.edit", "Edit"),
                    Map.entry("common.delete", "Delete")
            )
    );

    @GetMapping("/{lang}")
    public ResponseEntity<Map<String, String>> getDictionary(@PathVariable(name = "lang") String lang) {
        String safeLang = lang != null ? lang.toLowerCase() : "ru";
        var dict = DICTIONARIES.getOrDefault(safeLang, DICTIONARIES.get("ru"));
        return ResponseEntity.ok(dict);
    }
}
