package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.kauth.api.BindChannelRequest;
import com.smartup24.cms.instance.kauth.api.ChannelVerification;
import com.smartup24.cms.instance.kauth.api.ChannelView;
import com.smartup24.cms.instance.kauth.api.ConfirmChannelRequest;
import com.smartup24.cms.instance.kauth.service.KauthChannelService;
import com.smartup24.cms.instance.md.pref.MdPref;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(
            summary = "List my delivery channels",
            description = "The delivery channels (e-mail, messengers) bound to the caller's profile.")
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_channels")
    public ResponseEntity<List<ChannelView>> listChannels() {
        return ResponseEntity.ok(channelService.listChannels(SecurityContext.getCurrentUserId()));
    }

    /** Starts binding a channel: 202, the binding waits for the code sent to the address (POST /confirm). */
    @Operation(
            summary = "Start binding a channel",
            description = "Sends a confirmation code to the address; the binding completes with the confirm call.")
    @PostMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_channels")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<ChannelVerification> bindChannel(@Valid @RequestBody BindChannelRequest body) {
        String verifyToken = channelService.bindChannel(SecurityContext.getPrincipal(), body.channel(), body.address());
        return ResponseEntity.accepted().body(new ChannelVerification(verifyToken));
    }

    @Operation(
            summary = "Confirm a channel",
            description = "Completes the binding of a delivery channel with the code sent to its address.")
    @PostMapping("/confirm")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_channels")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> confirmChannel(@Valid @RequestBody ConfirmChannelRequest body) {
        channelService.confirmChannel(SecurityContext.getPrincipal(), body.verifyToken(), body.code());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Unbind a channel", description = "Removes a delivery channel from the caller's profile.")
    @DeleteMapping("/{channel}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_channels")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> unbindChannel(@PathVariable("channel") String channel) {
        channelService.unbindChannel(SecurityContext.getCurrentUserId(), channel);
        return ResponseEntity.noContent().build();
    }
}
