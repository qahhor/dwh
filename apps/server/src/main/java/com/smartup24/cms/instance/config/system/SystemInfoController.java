package com.smartup24.cms.instance.config.system;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.api.MdPref;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private final SystemInfoService service;

    public SystemInfoController(SystemInfoService service) {
        this.service = service;
    }

    @Operation(summary = "Get system information", description = "The version and build of the running installation.")
    @GetMapping("/info")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public SystemInfoResponse getInfo() {
        return service.getInfo();
    }
}
