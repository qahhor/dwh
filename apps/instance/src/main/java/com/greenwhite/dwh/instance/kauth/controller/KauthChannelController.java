package com.greenwhite.dwh.instance.kauth.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.kauth.repository.KauthChannelRepository;
import com.greenwhite.dwh.instance.kauth.service.KauthChannelService;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Свои каналы связи (FR-AUTH-5): привязка, подтверждение владения, отвязка.
 *
 * Право {@code iam.profile:manage_channels} существовало в каталоге, но за ним
 * не стояло ни одного эндпоинта — синхронизация каталога с кодом пометила его
 * устаревшим. Эти эндпоинты возвращают его к жизни.
 *
 * Пользователь управляет только своими каналами: идентификатор берётся из
 * контекста аутентификации, а не из запроса.
 */
@RestController
@RequestMapping("/api/v1/iam/profile/channels")
public class KauthChannelController {

    private final KauthChannelService channelService;

    public KauthChannelController(KauthChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_channels")
    public ResponseEntity<List<KauthChannelRepository.ChannelRecord>> listChannels() {
        return ResponseEntity.ok(channelService.listChannels(SecurityContext.getCurrentUserId()));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_channels")
    public ResponseEntity<Map<String, String>> bindChannel(@Valid @RequestBody BindChannelDto body) {
        String verifyToken = channelService.bindChannel(
                SecurityContext.getCurrentUserId(), body.channel(), body.address());
        return ResponseEntity.ok(Map.of("verifyToken", verifyToken));
    }

    @PostMapping("/confirm")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_channels")
    public ResponseEntity<Void> confirmChannel(@Valid @RequestBody ConfirmChannelDto body) {
        channelService.confirmChannel(SecurityContext.getCurrentUserId(), body.verifyToken(), body.code());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{channel}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_channels")
    public ResponseEntity<Void> unbindChannel(@PathVariable("channel") String channel) {
        channelService.unbindChannel(SecurityContext.getCurrentUserId(), channel);
        return ResponseEntity.noContent().build();
    }

    public record BindChannelDto(@NotBlank String channel, @NotBlank String address) {}

    public record ConfirmChannelDto(@NotBlank String verifyToken, @NotBlank String code) {}
}
