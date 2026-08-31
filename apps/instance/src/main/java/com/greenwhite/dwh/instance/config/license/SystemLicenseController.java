package com.greenwhite.dwh.instance.config.license;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Эндпоинт информации о лицензии, версии и связи с Control Plane.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemLicenseController {

    private final LicenseGateService licenseService;
    private final String appVersion;

    public SystemLicenseController(LicenseGateService licenseService,
                                   ObjectProvider<BuildProperties> buildProperties) {
        this.licenseService = licenseService;
        this.appVersion = buildProperties.getIfAvailable() != null
                ? buildProperties.getObject().getVersion() : "1.0.0";
    }

    @GetMapping("/license-info")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<Map<String, Object>> getLicenseInfo() {
        return ResponseEntity.ok(Map.of(
                "clientCode", licenseService.getClientCode(),
                "clientName", licenseService.getClientName(),
                "licenseStatus", licenseService.getStatus(),
                "resourceProfile", licenseService.getProfile(),
                "controlPlaneConfigured", licenseService.isControlPlaneConfigured(),
                "lastHeartbeatAt", licenseService.getLastHeartbeatAt() != null ? licenseService.getLastHeartbeatAt().toString() : "",
                "appVersion", appVersion,
                "schemaVersion", licenseService.getSchemaVersion(),
                "writeAllowed", licenseService.isWriteAllowed(),
                "accessAllowed", licenseService.isAccessAllowed()
        ));
    }
}
