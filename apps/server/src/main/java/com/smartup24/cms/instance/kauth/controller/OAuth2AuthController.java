package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.kauth.service.OAuth2AuthService;
import com.smartup24.cms.instance.md.pref.MdPref;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth/oauth2")
public class OAuth2AuthController {

    private final OAuth2AuthService oauth2AuthService;

    public OAuth2AuthController(OAuth2AuthService oauth2AuthService) {
        this.oauth2AuthService = oauth2AuthService;
    }

    @GetMapping("/providers")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<List<OAuth2AuthService.SsoProviderPublicDto>> getProviders() {
        return ResponseEntity.ok(oauth2AuthService.getEnabledProviders());
    }
}
