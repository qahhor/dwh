package com.greenwhite.dwh.instance.config.system;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdPref;
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

    @GetMapping("/info")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public SystemInfoResponse getInfo() {
        return service.getInfo();
    }
}
