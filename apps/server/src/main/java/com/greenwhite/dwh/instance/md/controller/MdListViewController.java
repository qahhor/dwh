package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdListViewRepository.ListView;
import com.greenwhite.dwh.instance.md.service.MdListViewService;
import com.greenwhite.dwh.instance.md.service.MdListViewService.ViewData;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Свои сохранённые представления списка. Как и собственные настройки, это часть профиля: право —
 * от формы профиля, которая есть у всех ролей; доступ к самому списку сервис проверяет по реестру.
 */
@RestController
@RequestMapping("/api/v1/list-views/{listCode}")
public class MdListViewController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MdListViewService service;

    public MdListViewController(MdListViewService service) {
        this.service = service;
    }

    public record ViewRequest(String name, JsonNode state, Boolean isDefault, Integer lockVersion) {
        ViewData toData() {
            return new ViewData(name, state, Boolean.TRUE.equals(isDefault));
        }
    }

    public record ViewResponse(long id, String name, JsonNode state, boolean isDefault, int lockVersion,
                               Instant modifiedAt) {
        static ViewResponse of(ListView view) {
            return new ViewResponse(view.id(), view.name(), JSON.readTree(view.stateJson()), view.isDefault(),
                    view.lockVersion(), view.modifiedAt());
        }
    }

    private static long userId() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<ViewResponse>> list(@PathVariable String listCode) {
        return ResponseEntity.ok(service.list(userId(), listCode).stream().map(ViewResponse::of).toList());
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    public ResponseEntity<ViewResponse> create(@PathVariable String listCode, @RequestBody ViewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ViewResponse.of(service.create(userId(), listCode, request.toData())));
    }

    @PutMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    public ResponseEntity<ViewResponse> update(@PathVariable String listCode, @PathVariable long id,
                                               @RequestBody ViewRequest request) {
        if (request.lockVersion() == null) {
            throw ApiException.validation(MdListViewService.LIST_VIEW_INVALID, List.of(
                    new FieldErrorItem("lockVersion", MdListViewService.LIST_VIEW_INVALID, "lockVersion required")));
        }
        return ResponseEntity.ok(ViewResponse.of(
                service.update(userId(), listCode, id, request.lockVersion(), request.toData())));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    public ResponseEntity<Void> delete(@PathVariable String listCode, @PathVariable long id) {
        service.delete(userId(), listCode, id);
        return ResponseEntity.noContent().build();
    }
}
