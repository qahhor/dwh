package com.greenwhite.dwh.instance.kauth.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.kauth.service.OAuth2AuthService;
import com.greenwhite.dwh.instance.md.pref.MdPref;
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
