package com.greenwhite.dwh.instance.config.system;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemInfoControllerTest {

    @Test
    void exposesOnlySanitizedLocalSystemState() throws Exception {
        SystemInfoService service = mock(SystemInfoService.class);
        when(service.getInfo()).thenReturn(new SystemInfoResponse(
                "1.0.0",
                "019",
                new SystemInfoResponse.Organization("acme", "Acme", "M"),
                "local_disk",
                Map.of(
                        "database", new SystemInfoResponse.Component("UP"),
                        "storage", new SystemInfoResponse.Component("UP"),
                        "typesense", new SystemInfoResponse.Component("DISABLED")),
                new BackupStatus(
                        "SUCCESS",
                        Instant.parse("2026-09-02T03:00:00Z"),
                        null,
                        "CURRENT",
                        3_600L,
                        86_400L),
                Instant.parse("2026-09-04T10:15:30Z")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SystemInfoController(service)).build();

        String body = mvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appVersion").value("1.0.0"))
                .andExpect(jsonPath("$.schemaVersion").value("019"))
                .andExpect(jsonPath("$.organization.code").value("acme"))
                .andExpect(jsonPath("$.organization.name").value("Acme"))
                .andExpect(jsonPath("$.organization.resourceProfile").value("M"))
                .andExpect(jsonPath("$.storageProvider").value("local_disk"))
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.backup.status").value("SUCCESS"))
                .andExpect(jsonPath("$.backup.freshness").value("CURRENT"))
                .andExpect(jsonPath("$.backup.ageSeconds").value(3_600))
                .andExpect(jsonPath("$.backup.maxAgeSeconds").value(86_400))
                .andExpect(jsonPath("$.checkedAt").value("2026-09-04T10:15:30Z"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(
                "license", "License", "controlPlane", "heartbeat",
                "jdbc:", "accessKey", "secretKey", "/var/lib");
    }

    @Test
    void oldLicenseEndpointDoesNotExist() throws Exception {
        SystemInfoService service = mock(SystemInfoService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SystemInfoController(service)).build();

        mvc.perform(get("/api/v1/system/license-info")).andExpect(status().isNotFound());
    }

    @Test
    void requiresSettingsViewPermission() throws Exception {
        RequiresPermission permission = SystemInfoController.class
                .getMethod("getInfo")
                .getAnnotation(RequiresPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.form()).isEqualTo(MdPref.FORM_SETTINGS);
        assertThat(permission.action()).isEqualTo("view");
    }
}
