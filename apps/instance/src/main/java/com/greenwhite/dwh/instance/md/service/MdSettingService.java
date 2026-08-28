package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.instance.md.repository.MdSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class MdSettingService {

    private final MdSettingRepository settingRepository;

    public MdSettingService(MdSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, String> getEffectiveSettings(Long userId) {
        Map<String, String> effective = new HashMap<>(settingRepository.getAllInstanceSettings());
        if (userId != null) {
            effective.putAll(settingRepository.getAllUserSettings(userId));
        }
        return effective;
    }

    @Transactional
    public void updateInstanceSettings(Map<String, String> settings) {
        if (settings != null) {
            settings.forEach(settingRepository::setInstanceSetting);
        }
    }

    @Transactional
    public void updateUserSetting(Long userId, String key, String value) {
        settingRepository.setUserSetting(userId, key, value);
    }
}
