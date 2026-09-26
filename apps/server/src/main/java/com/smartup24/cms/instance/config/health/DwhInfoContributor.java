package com.smartup24.cms.instance.config.health;

import com.smartup24.cms.instance.config.system.SystemInfoService;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
public class DwhInfoContributor implements InfoContributor {

    private final SystemInfoService systemInfoService;

    public DwhInfoContributor(SystemInfoService systemInfoService) {
        this.systemInfoService = systemInfoService;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("smartupcms", systemInfoService.getInfo());
    }
}
