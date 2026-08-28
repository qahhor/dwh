package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class MdPermissionService {

    private final MdPermissionRepository permissionRepository;

    public MdPermissionService(MdPermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    @Transactional(readOnly = true)
    public Set<String> getEffectivePermissions(Long userId) {
        return permissionRepository.getEffectivePermissionsForUser(userId);
    }

    @Transactional(readOnly = true)
    public long getPermissionVersion(Long userId) {
        return permissionRepository.getPermissionVersion(userId);
    }

    @Transactional
    public void recalculateEffectivePermissions(Long userId) {
        permissionRepository.recalculateEffectivePermissions(userId);
    }

    @Transactional(readOnly = true)
    public List<MdPermissionRepository.FormTreeItem> getFormCatalog() {
        return permissionRepository.getAllFormsWithActions();
    }

    @Transactional
    public void initSystemFormsIfEmpty() {
        permissionRepository.registerForm(MdPref.FORM_PROFILE, MdPref.MODULE_CODE, "Мой профиль");
        permissionRepository.registerFormAction(MdPref.FORM_PROFILE, "view", "Просмотр профиля");
        permissionRepository.registerFormAction(MdPref.FORM_PROFILE, "update", "Изменение данных");
        permissionRepository.registerFormAction(MdPref.FORM_PROFILE, "change_password", "Смена пароля");
        permissionRepository.registerFormAction(MdPref.FORM_PROFILE, "manage_channels", "Управление каналами");
        permissionRepository.registerFormAction(MdPref.FORM_PROFILE, "manage_tokens", "Управление токенами");

        permissionRepository.registerForm(MdPref.FORM_USERS, MdPref.MODULE_CODE, "Пользователи");
        permissionRepository.registerFormAction(MdPref.FORM_USERS, "view", "Просмотр списка");
        permissionRepository.registerFormAction(MdPref.FORM_USERS, "create", "Создание пользователя");
        permissionRepository.registerFormAction(MdPref.FORM_USERS, "update", "Редактирование");
        permissionRepository.registerFormAction(MdPref.FORM_USERS, "block", "Блокировка");
        permissionRepository.registerFormAction(MdPref.FORM_USERS, "unblock", "Разблокировка");

        permissionRepository.registerForm(MdPref.FORM_ROLES, MdPref.MODULE_CODE, "Роли и права");
        permissionRepository.registerFormAction(MdPref.FORM_ROLES, "view", "Просмотр ролей");
        permissionRepository.registerFormAction(MdPref.FORM_ROLES, "create", "Создание роли");
        permissionRepository.registerFormAction(MdPref.FORM_ROLES, "update", "Редактирование");
        permissionRepository.registerFormAction(MdPref.FORM_ROLES, "delete", "Удаление");
        permissionRepository.registerFormAction(MdPref.FORM_ROLES, "grant", "Настройка матрицы прав");

        permissionRepository.registerForm(MdPref.FORM_CUSTOM_FIELDS, MdPref.MODULE_CODE, "Динамические поля");
        permissionRepository.registerFormAction(MdPref.FORM_CUSTOM_FIELDS, "view", "Просмотр полей");
        permissionRepository.registerFormAction(MdPref.FORM_CUSTOM_FIELDS, "create", "Создание поля");
        permissionRepository.registerFormAction(MdPref.FORM_CUSTOM_FIELDS, "update", "Редактирование");
        permissionRepository.registerFormAction(MdPref.FORM_CUSTOM_FIELDS, "delete", "Удаление");

        permissionRepository.registerForm(MdPref.FORM_SETTINGS, MdPref.MODULE_CODE, "Настройки экземпляра");
        permissionRepository.registerFormAction(MdPref.FORM_SETTINGS, "view", "Просмотр настроек");
        permissionRepository.registerFormAction(MdPref.FORM_SETTINGS, "update", "Изменение настроек");
    }
}
